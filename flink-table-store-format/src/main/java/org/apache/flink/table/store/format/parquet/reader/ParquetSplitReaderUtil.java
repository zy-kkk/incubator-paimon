/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.format.parquet.reader;

import org.apache.flink.table.store.data.columnar.heap.HeapArrayVector;
import org.apache.flink.table.store.data.columnar.heap.HeapBooleanVector;
import org.apache.flink.table.store.data.columnar.heap.HeapByteVector;
import org.apache.flink.table.store.data.columnar.heap.HeapBytesVector;
import org.apache.flink.table.store.data.columnar.heap.HeapDoubleVector;
import org.apache.flink.table.store.data.columnar.heap.HeapFloatVector;
import org.apache.flink.table.store.data.columnar.heap.HeapIntVector;
import org.apache.flink.table.store.data.columnar.heap.HeapLongVector;
import org.apache.flink.table.store.data.columnar.heap.HeapMapVector;
import org.apache.flink.table.store.data.columnar.heap.HeapRowVector;
import org.apache.flink.table.store.data.columnar.heap.HeapShortVector;
import org.apache.flink.table.store.data.columnar.heap.HeapTimestampVector;
import org.apache.flink.table.store.data.columnar.writable.WritableColumnVector;
import org.apache.flink.table.store.format.parquet.ParquetSchemaConverter;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.RowType;

import org.apache.parquet.ParquetRuntimeException;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.InvalidSchemaException;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.apache.parquet.Preconditions.checkArgument;

/** Util for generating {@link ColumnReader}. */
public class ParquetSplitReaderUtil {

    @SuppressWarnings("rawtypes")
    public static ColumnReader createColumnReader(
            LogicalType fieldType,
            Type type,
            List<ColumnDescriptor> columnDescriptors,
            PageReadStore pages,
            int depth)
            throws IOException {
        List<ColumnDescriptor> descriptors =
                getAllColumnDescriptorByType(depth, type, columnDescriptors);
        switch (fieldType.getTypeRoot()) {
            case BOOLEAN:
                return new BooleanColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case TINYINT:
                return new ByteColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case DOUBLE:
                return new DoubleColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case FLOAT:
                return new FloatColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case INTEGER:
            case DATE:
            case TIME_WITHOUT_TIME_ZONE:
                return new IntColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case BIGINT:
                return new LongColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case SMALLINT:
                return new ShortColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case CHAR:
            case VARCHAR:
            case BINARY:
            case VARBINARY:
                return new BytesColumnReader(
                        descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case TIMESTAMP_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                return new TimestampColumnReader(
                        true, descriptors.get(0), pages.getPageReader(descriptors.get(0)));
            case DECIMAL:
                switch (descriptors.get(0).getPrimitiveType().getPrimitiveTypeName()) {
                    case INT32:
                        return new IntColumnReader(
                                descriptors.get(0), pages.getPageReader(descriptors.get(0)));
                    case INT64:
                        return new LongColumnReader(
                                descriptors.get(0), pages.getPageReader(descriptors.get(0)));
                    case BINARY:
                        return new BytesColumnReader(
                                descriptors.get(0), pages.getPageReader(descriptors.get(0)));
                    case FIXED_LEN_BYTE_ARRAY:
                        return new FixedLenBytesColumnReader(
                                descriptors.get(0),
                                pages.getPageReader(descriptors.get(0)),
                                ((DecimalType) fieldType).getPrecision());
                }
            case ARRAY:
                return new ArrayColumnReader(
                        descriptors.get(0),
                        pages.getPageReader(descriptors.get(0)),
                        true,
                        descriptors.get(0).getPrimitiveType(),
                        fieldType);
            case MAP:
                MapType mapType = (MapType) fieldType;
                ArrayColumnReader keyReader =
                        new ArrayColumnReader(
                                descriptors.get(0),
                                pages.getPageReader(descriptors.get(0)),
                                true,
                                descriptors.get(0).getPrimitiveType(),
                                new ArrayType(mapType.getKeyType()));
                ArrayColumnReader valueReader =
                        new ArrayColumnReader(
                                descriptors.get(1),
                                pages.getPageReader(descriptors.get(1)),
                                true,
                                descriptors.get(1).getPrimitiveType(),
                                new ArrayType(mapType.getValueType()));
                return new MapColumnReader(keyReader, valueReader);
            case ROW:
                RowType rowType = (RowType) fieldType;
                GroupType groupType = type.asGroupType();
                List<ColumnReader> fieldReaders = new ArrayList<>();
                for (int i = 0; i < rowType.getFieldCount(); i++) {
                    fieldReaders.add(
                            createColumnReader(
                                    rowType.getTypeAt(i),
                                    groupType.getType(i),
                                    descriptors,
                                    pages,
                                    depth + 1));
                }
                return new RowColumnReader(fieldReaders);
            default:
                throw new UnsupportedOperationException(fieldType + " is not supported now.");
        }
    }

    public static WritableColumnVector createWritableColumnVector(
            int batchSize,
            LogicalType fieldType,
            Type type,
            List<ColumnDescriptor> columnDescriptors,
            int depth) {
        List<ColumnDescriptor> descriptors =
                getAllColumnDescriptorByType(depth, type, columnDescriptors);
        PrimitiveType primitiveType = descriptors.get(0).getPrimitiveType();
        PrimitiveType.PrimitiveTypeName typeName = primitiveType.getPrimitiveTypeName();
        switch (fieldType.getTypeRoot()) {
            case BOOLEAN:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.BOOLEAN,
                        "Unexpected type: %s",
                        typeName);
                return new HeapBooleanVector(batchSize);
            case TINYINT:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.INT32,
                        "Unexpected type: %s",
                        typeName);
                return new HeapByteVector(batchSize);
            case DOUBLE:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.DOUBLE,
                        "Unexpected type: %s",
                        typeName);
                return new HeapDoubleVector(batchSize);
            case FLOAT:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.FLOAT,
                        "Unexpected type: %s",
                        typeName);
                return new HeapFloatVector(batchSize);
            case INTEGER:
            case DATE:
            case TIME_WITHOUT_TIME_ZONE:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.INT32,
                        "Unexpected type: %s",
                        typeName);
                return new HeapIntVector(batchSize);
            case BIGINT:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.INT64,
                        "Unexpected type: %s",
                        typeName);
                return new HeapLongVector(batchSize);
            case SMALLINT:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.INT32,
                        "Unexpected type: %s",
                        typeName);
                return new HeapShortVector(batchSize);
            case CHAR:
            case VARCHAR:
            case BINARY:
            case VARBINARY:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.BINARY,
                        "Unexpected type: %s",
                        typeName);
                return new HeapBytesVector(batchSize);
            case TIMESTAMP_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                checkArgument(
                        typeName == PrimitiveType.PrimitiveTypeName.INT96,
                        "Unexpected type: %s",
                        typeName);
                return new HeapTimestampVector(batchSize);
            case DECIMAL:
                DecimalType decimalType = (DecimalType) fieldType;
                if (ParquetSchemaConverter.is32BitDecimal(decimalType.getPrecision())) {
                    checkArgument(
                            (typeName == PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
                                            || typeName == PrimitiveType.PrimitiveTypeName.INT32)
                                    && primitiveType.getOriginalType() == OriginalType.DECIMAL,
                            "Unexpected type: %s",
                            typeName);
                    return new HeapIntVector(batchSize);
                } else if (ParquetSchemaConverter.is64BitDecimal(decimalType.getPrecision())) {
                    checkArgument(
                            (typeName == PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
                                            || typeName == PrimitiveType.PrimitiveTypeName.INT64)
                                    && primitiveType.getOriginalType() == OriginalType.DECIMAL,
                            "Unexpected type: %s",
                            typeName);
                    return new HeapLongVector(batchSize);
                } else {
                    checkArgument(
                            (typeName == PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
                                            || typeName == PrimitiveType.PrimitiveTypeName.BINARY)
                                    && primitiveType.getOriginalType() == OriginalType.DECIMAL,
                            "Unexpected type: %s",
                            typeName);
                    return new HeapBytesVector(batchSize);
                }
            case ARRAY:
                ArrayType arrayType = (ArrayType) fieldType;
                return new HeapArrayVector(
                        batchSize,
                        createWritableColumnVector(
                                batchSize,
                                arrayType.getElementType(),
                                type,
                                columnDescriptors,
                                depth));
            case MAP:
                MapType mapType = (MapType) fieldType;
                GroupType repeatedType = type.asGroupType().getType(0).asGroupType();
                return new HeapMapVector(
                        batchSize,
                        createWritableColumnVector(
                                batchSize,
                                mapType.getKeyType(),
                                repeatedType.getType(0),
                                descriptors,
                                depth + 2),
                        createWritableColumnVector(
                                batchSize,
                                mapType.getValueType(),
                                repeatedType.getType(1),
                                descriptors,
                                depth + 2));
            case ROW:
                RowType rowType = (RowType) fieldType;
                GroupType groupType = type.asGroupType();
                WritableColumnVector[] columnVectors =
                        new WritableColumnVector[rowType.getFieldCount()];
                for (int i = 0; i < columnVectors.length; i++) {
                    columnVectors[i] =
                            createWritableColumnVector(
                                    batchSize,
                                    rowType.getTypeAt(i),
                                    groupType.getType(i),
                                    descriptors,
                                    depth + 1);
                }
                return new HeapRowVector(batchSize, columnVectors);
            default:
                throw new UnsupportedOperationException(fieldType + " is not supported now.");
        }
    }

    private static List<ColumnDescriptor> getAllColumnDescriptorByType(
            int depth, Type type, List<ColumnDescriptor> columns) throws ParquetRuntimeException {
        List<ColumnDescriptor> res = new ArrayList<>();
        for (ColumnDescriptor descriptor : columns) {
            if (depth >= descriptor.getPath().length) {
                throw new InvalidSchemaException("Corrupted Parquet schema");
            }
            if (type.getName().equals(descriptor.getPath()[depth])) {
                res.add(descriptor);
            }
        }

        // If doesn't find the type descriptor in corresponding depth, throw exception
        if (res.isEmpty()) {
            throw new InvalidSchemaException(
                    "Failed to find related Parquet column descriptor with type " + type);
        }
        return res;
    }
}