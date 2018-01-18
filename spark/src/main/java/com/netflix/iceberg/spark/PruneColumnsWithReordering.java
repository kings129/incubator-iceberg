/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.spark;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.types.Type;
import com.netflix.iceberg.types.Type.TypeID;
import com.netflix.iceberg.types.TypeUtil;
import com.netflix.iceberg.types.Types;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.BinaryType;
import org.apache.spark.sql.types.BooleanType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.DoubleType;
import org.apache.spark.sql.types.FloatType;
import org.apache.spark.sql.types.IntegerType;
import org.apache.spark.sql.types.LongType;
import org.apache.spark.sql.types.MapType;
import org.apache.spark.sql.types.StringType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.types.TimestampType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class PruneColumnsWithReordering extends TypeUtil.CustomOrderSchemaVisitor<Type> {
  private final StructType requestedType;
  private final Set<Integer> filterRefs;
  private DataType current = null;

  PruneColumnsWithReordering(StructType requestedType, Set<Integer> filterRefs) {
    this.requestedType = requestedType;
    this.filterRefs = filterRefs;
  }

  @Override
  public Type schema(Schema schema, Supplier<Type> structResult) {
    this.current = requestedType;
    try {
      return structResult.get();
    } finally {
      this.current = null;
    }
  }

  @Override
  public Type struct(Types.StructType struct, Iterable<Type> fieldResults) {
    Preconditions.checkNotNull(struct, "Cannot prune null struct. Pruning must start with a schema.");
    Preconditions.checkArgument(current instanceof StructType, "Not a struct: %s", current);
    StructType s = (StructType) current;

    List<Types.NestedField> fields = struct.fields();
    List<Type> types = Lists.newArrayList(fieldResults);

    boolean changed = false;
    // use a LinkedHashMap to preserve the original order of filter fields that are not projected
    Map<String, Types.NestedField> projectedFields = Maps.newLinkedHashMap();
    for (int i = 0; i < fields.size(); i += 1) {
      Types.NestedField field = fields.get(i);
      Type type = types.get(i);

      if (type == null) {
        changed = true;

      } else if (field.type() == type) {
        projectedFields.put(field.name(), field);

      } else if (field.isOptional()) {
        changed = true;
        projectedFields.put(field.name(),
            Types.NestedField.optional(field.fieldId(), field.name(), type));

      } else {
        changed = true;
        projectedFields.put(field.name(),
            Types.NestedField.required(field.fieldId(), field.name(), type));
      }
    }

    // Construct a new struct with the projected struct's order
    boolean reordered = false;
    StructField[] requestedFields = s.fields();
    List<Types.NestedField> newFields = Lists.newArrayListWithExpectedSize(requestedFields.length);
    for (int i = 0; i < requestedFields.length; i += 1) {
      // fields are resolved by name because Spark only sees the current table schema.
      String name = requestedFields[i].name();
      if (!fields.get(i).name().equals(name)) {
        reordered = true;
      }
      newFields.add(projectedFields.remove(name));
    }

    // Add remaining filter fields that were not explicitly projected
    if (!projectedFields.isEmpty()) {
      newFields.addAll(projectedFields.values());
      changed = true; // order probably changed
    }

    if (reordered || changed) {
      return Types.StructType.of(newFields);
    }

    return struct;
  }

  @Override
  public Type field(Types.NestedField field, Supplier<Type> fieldResult) {
    Preconditions.checkArgument(current instanceof StructType, "Not a struct: %s", current);
    StructType struct = (StructType) current;

    // fields are resolved by name because Spark only sees the current table schema.
    if (struct.getFieldIndex(field.name()).isEmpty()) {
      // make sure that filter fields are projected even if they aren't in the requested schema.
      if (filterRefs.contains(field.fieldId())) {
        return field.type();
      }
      return null;
    }

    int fieldIndex = struct.fieldIndex(field.name());
    StructField f = struct.fields()[fieldIndex];

    Preconditions.checkArgument(f.nullable() || field.isRequired(),
        "Cannot project an optional field as non-null: %s", field.name());

    this.current = f.dataType();
    try {
      return fieldResult.get();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid projection for field " + field.name() + ": " + e.getMessage(), e);
    } finally {
      this.current = struct;
    }
  }

  @Override
  public Type list(Types.ListType list, Supplier<Type> elementResult) {
    Preconditions.checkArgument(current instanceof ArrayType, "Not an array: %s", current);
    ArrayType array = (ArrayType) current;

    Preconditions.checkArgument(array.containsNull() || !list.isElementOptional(),
        "Cannot project an array of optional elements as required elements: %s", array);

    this.current = array.elementType();
    try {
      Type elementType = elementResult.get();
      if (list.elementType() == elementType) {
        return list;
      }

      // must be a projected element type, create a new list
      if (list.isElementOptional()) {
        return Types.ListType.ofOptional(list.elementId(), elementType);
      } else {
        return Types.ListType.ofRequired(list.elementId(), elementType);
      }
    } finally {
      this.current = array;
    }
  }

  @Override
  public Type map(Types.MapType map, Supplier<Type> valueResult) {
    Preconditions.checkArgument(current instanceof MapType, "Not a map: %s", current);
    MapType m = (MapType) current;

    Preconditions.checkArgument(m.valueContainsNull() || !map.isValueOptional(),
        "Cannot project a map of optional values as required values: %s", map);
    Preconditions.checkArgument(StringType.class.isInstance(m.keyType()),
        "Invalid map key type (not string): %s", m.keyType());

    this.current = m.valueType();
    try {
      Type valueType = valueResult.get();
      if (map.valueType() == valueType) {
        return map;
      }

      if (map.isValueOptional()) {
        return Types.MapType.ofOptional(map.keyId(), map.valueId(), valueType);
      } else {
        return Types.MapType.ofRequired(map.keyId(), map.valueId(), valueType);
      }
    } finally {
      this.current = m;
    }
  }

  @Override
  public Type primitive(Type.PrimitiveType primitive) {
    Class<? extends DataType> expectedType = TYPES.get(primitive.typeId());
    Preconditions.checkArgument(expectedType != null && expectedType.isInstance(current),
        "Cannot project %s to incompatible type: %s", primitive, current);

    // additional checks based on type
    switch (primitive.typeId()) {
      case DECIMAL:
        Types.DecimalType decimal = (Types.DecimalType) primitive;
        DecimalType d = (DecimalType) current;
        Preconditions.checkArgument(d.scale() == decimal.scale(),
            "Cannot project decimal with incompatible scale: %s != %s", d.scale(), decimal.scale());
        Preconditions.checkArgument(d.precision() >= decimal.precision(),
            "Cannot project decimal with incompatible precision: %s < %s",
            d.precision(), decimal.precision());
        break;
      case TIMESTAMP:
        Types.TimestampType timestamp = (Types.TimestampType) primitive;
        Preconditions.checkArgument(timestamp.shouldAdjustToUTC(),
            "Cannot project timestamp (without time zone) as timestamptz (with time zone)");
        break;
      default:
    }

    return primitive;
  }

  private static final Map<TypeID, Class<? extends DataType>> TYPES = ImmutableMap
      .<TypeID, Class<? extends DataType>>builder()
      .put(TypeID.BOOLEAN, BooleanType.class)
      .put(TypeID.INTEGER, IntegerType.class)
      .put(TypeID.LONG, LongType.class)
      .put(TypeID.FLOAT, FloatType.class)
      .put(TypeID.DOUBLE, DoubleType.class)
      .put(TypeID.DATE, DateType.class)
      .put(TypeID.TIMESTAMP, TimestampType.class)
      .put(TypeID.DECIMAL, DecimalType.class)
      .put(TypeID.UUID, StringType.class)
      .put(TypeID.STRING, StringType.class)
      .put(TypeID.FIXED, BinaryType.class)
      .put(TypeID.BINARY, BinaryType.class)
      .build();
}