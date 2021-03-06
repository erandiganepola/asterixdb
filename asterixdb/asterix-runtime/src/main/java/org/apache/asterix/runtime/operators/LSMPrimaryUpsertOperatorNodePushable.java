/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.runtime.operators;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.common.dataflow.LSMIndexUtil;
import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.common.transactions.ILogMarkerCallback;
import org.apache.asterix.common.transactions.PrimaryIndexLogMarkerCallback;
import org.apache.asterix.om.pointables.nonvisitor.ARecordPointable;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.transaction.management.opcallbacks.AbstractIndexModificationOperationCallback;
import org.apache.asterix.transaction.management.opcallbacks.AbstractIndexModificationOperationCallback.Operation;
import org.apache.asterix.transaction.management.opcallbacks.LockThenSearchOperationCallback;
import org.apache.hyracks.api.comm.VSizeFrame;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IMissingWriter;
import org.apache.hyracks.api.dataflow.value.IRecordDescriptorProvider;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import org.apache.hyracks.dataflow.common.comm.io.ArrayTupleReference;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAppender;
import org.apache.hyracks.dataflow.common.comm.util.FrameUtils;
import org.apache.hyracks.dataflow.common.data.accessors.FrameTupleReference;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.apache.hyracks.dataflow.common.utils.TaskUtil;
import org.apache.hyracks.storage.am.btree.impls.RangePredicate;
import org.apache.hyracks.storage.am.btree.util.BTreeUtils;
import org.apache.hyracks.storage.am.common.api.IIndexCursor;
import org.apache.hyracks.storage.am.common.api.ITreeIndex;
import org.apache.hyracks.storage.am.common.dataflow.IIndexOperatorDescriptor;
import org.apache.hyracks.storage.am.common.ophelpers.IndexOperation;
import org.apache.hyracks.storage.am.common.ophelpers.MultiComparator;
import org.apache.hyracks.storage.am.common.tuples.PermutingFrameTupleReference;
import org.apache.hyracks.storage.am.lsm.common.api.IFrameOperationCallback;
import org.apache.hyracks.storage.am.lsm.common.api.IFrameOperationCallbackFactory;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexAccessor;
import org.apache.hyracks.storage.am.lsm.common.dataflow.LSMIndexInsertUpdateDeleteOperatorNodePushable;
import org.apache.hyracks.storage.am.lsm.common.impls.AbstractLSMIndex;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMTreeIndexAccessor;

public class LSMPrimaryUpsertOperatorNodePushable extends LSMIndexInsertUpdateDeleteOperatorNodePushable {

    private final PermutingFrameTupleReference key;
    private MultiComparator keySearchCmp;
    private ArrayTupleBuilder missingTupleBuilder;
    private final IMissingWriter missingWriter;
    private ArrayTupleBuilder tb;
    private DataOutput dos;
    private RangePredicate searchPred;
    private IIndexCursor cursor;
    private ITupleReference prevTuple;
    private final int numOfPrimaryKeys;
    boolean isFiltered = false;
    private final ArrayTupleReference prevTupleWithFilter = new ArrayTupleReference();
    private ArrayTupleBuilder prevRecWithPKWithFilterValue;
    private ARecordType recordType;
    private int presetFieldIndex = -1;
    private ARecordPointable recPointable;
    private DataOutput prevDos;
    private final boolean hasMeta;
    private final int filterFieldIndex;
    private final int metaFieldIndex;
    private LockThenSearchOperationCallback searchCallback;
    private IFrameOperationCallback frameOpCallback;
    private final IFrameOperationCallbackFactory frameOpCallbackFactory;
    private AbstractIndexModificationOperationCallback abstractModCallback;
    private final boolean hasSecondaries;

    public LSMPrimaryUpsertOperatorNodePushable(IIndexOperatorDescriptor opDesc, IHyracksTaskContext ctx, int partition,
            int[] fieldPermutation, IRecordDescriptorProvider recordDescProvider, int numOfPrimaryKeys,
            ARecordType recordType, int filterFieldIndex, IFrameOperationCallbackFactory frameOpCallbackFactory,
            boolean hasSecondaries) throws HyracksDataException {
        super(opDesc, ctx, partition, fieldPermutation, recordDescProvider, IndexOperation.UPSERT);
        this.key = new PermutingFrameTupleReference();
        this.numOfPrimaryKeys = numOfPrimaryKeys;
        this.frameOpCallbackFactory = frameOpCallbackFactory;
        missingWriter = opDesc.getMissingWriterFactory().createMissingWriter();
        int[] searchKeyPermutations = new int[numOfPrimaryKeys];
        for (int i = 0; i < searchKeyPermutations.length; i++) {
            searchKeyPermutations[i] = fieldPermutation[i];
        }
        key.setFieldPermutation(searchKeyPermutations);
        hasMeta = (fieldPermutation.length > numOfPrimaryKeys + 1) && (filterFieldIndex < 0
                || (filterFieldIndex >= 0 && (fieldPermutation.length > numOfPrimaryKeys + 2)));
        this.metaFieldIndex = numOfPrimaryKeys + 1;
        this.filterFieldIndex = numOfPrimaryKeys + (hasMeta ? 2 : 1);
        if (filterFieldIndex >= 0) {
            isFiltered = true;
            this.recordType = recordType;
            this.presetFieldIndex = filterFieldIndex;
            this.recPointable = ARecordPointable.FACTORY.createPointable();
            this.prevRecWithPKWithFilterValue = new ArrayTupleBuilder(fieldPermutation.length + (hasMeta ? 1 : 0));
            this.prevDos = prevRecWithPKWithFilterValue.getDataOutput();
        }
        this.hasSecondaries = hasSecondaries;
    }

    // we have the permutation which has [pk locations, record location, optional:filter-location]
    // the index -> we don't need anymore data?
    // we need to use the primary index opTracker and secondary indexes callbacks for insert/delete since the lock would
    // have been obtained through searchForUpsert operation

    @Override
    public void open() throws HyracksDataException {
        RecordDescriptor inputRecDesc = recordDescProvider.getInputRecordDescriptor(opDesc.getActivityId(), 0);
        accessor = new FrameTupleAccessor(inputRecDesc);
        writeBuffer = new VSizeFrame(ctx);
        writer.open();
        indexHelper.open();
        index = indexHelper.getIndexInstance();
        try {
            if (ctx.getSharedObject() != null) {
                PrimaryIndexLogMarkerCallback callback = new PrimaryIndexLogMarkerCallback((AbstractLSMIndex) index);
                TaskUtil.putInSharedMap(ILogMarkerCallback.KEY_MARKER_CALLBACK, callback, ctx);
            }
            missingTupleBuilder = new ArrayTupleBuilder(1);
            DataOutput out = missingTupleBuilder.getDataOutput();
            try {
                missingWriter.writeMissing(out);
            } catch (IOException e) {
                throw new HyracksDataException(e);
            }
            missingTupleBuilder.addFieldEndOffset();
            searchPred = createSearchPredicate();
            tb = new ArrayTupleBuilder(recordDesc.getFieldCount());
            dos = tb.getDataOutput();
            appender = new FrameTupleAppender(new VSizeFrame(ctx), true);
            modCallback = opDesc.getModificationOpCallbackFactory()
                    .createModificationOperationCallback(indexHelper.getResource(), ctx, this);
            abstractModCallback = (AbstractIndexModificationOperationCallback) modCallback;
            searchCallback = (LockThenSearchOperationCallback) opDesc.getSearchOpCallbackFactory()
                    .createSearchOperationCallback(indexHelper.getResource().getId(), ctx, this);
            indexAccessor = index.createAccessor(abstractModCallback, searchCallback);

            cursor = indexAccessor.createSearchCursor(false);
            frameTuple = new FrameTupleReference();
            INcApplicationContext appCtx =
                    (INcApplicationContext) ctx.getJobletContext().getServiceContext().getApplicationContext();
            LSMIndexUtil.checkAndSetFirstLSN((AbstractLSMIndex) index,
                    appCtx.getTransactionSubsystem().getLogManager());
            frameOpCallback =
                    frameOpCallbackFactory.createFrameOperationCallback(ctx, (ILSMIndexAccessor) indexAccessor);
        } catch (Exception e) {
            indexHelper.close();
            throw new HyracksDataException(e);
        }
    }

    private void resetSearchPredicate(int tupleIndex) {
        key.reset(accessor, tupleIndex);
        searchPred.reset(key, key, true, true, keySearchCmp, keySearchCmp);
    }

    private void writeOutput(int tupleIndex, boolean recordWasInserted, boolean recordWasDeleted) throws IOException {
        if (recordWasInserted || recordWasDeleted) {
            frameTuple.reset(accessor, tupleIndex);
            for (int i = 0; i < frameTuple.getFieldCount(); i++) {
                dos.write(frameTuple.getFieldData(i), frameTuple.getFieldStart(i), frameTuple.getFieldLength(i));
                tb.addFieldEndOffset();
            }
            FrameUtils.appendToWriter(writer, appender, tb.getFieldEndOffsets(), tb.getByteArray(), 0, tb.getSize());
        } else {
            try {
                searchCallback.release();
            } catch (ACIDException e) {
                throw new HyracksDataException(e);
            }
        }
    }

    public static boolean isNull(ITupleReference t1, int field) {
        return t1.getFieldData(field)[t1.getFieldStart(field)] == ATypeTag.SERIALIZED_MISSING_TYPE_TAG;
    }

    private void addNullField() throws IOException {
        dos.write(missingTupleBuilder.getByteArray());
        tb.addFieldEndOffset();
    }

    @Override
    public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
        accessor.reset(buffer);
        LSMTreeIndexAccessor lsmAccessor = (LSMTreeIndexAccessor) indexAccessor;
        int tupleCount = accessor.getTupleCount();
        boolean firstModification = true;
        int i = 0;
        try {
            while (i < tupleCount) {
                tb.reset();
                boolean recordWasInserted = false;
                tuple.reset(accessor, i);
                resetSearchPredicate(i);
                if (hasSecondaries || isNull(tuple, numOfPrimaryKeys)) {
                    lsmAccessor.search(cursor, searchPred);
                    if (cursor.hasNext()) {
                        cursor.next();
                        prevTuple = cursor.getTuple();
                        cursor.reset();
                        if (isFiltered) {
                            prevTuple = getPrevTupleWithFilter(prevTuple);
                        }
                        dos.write(prevTuple.getFieldData(numOfPrimaryKeys), prevTuple.getFieldStart(numOfPrimaryKeys),
                                prevTuple.getFieldLength(numOfPrimaryKeys));
                        tb.addFieldEndOffset();
                        // if has meta, then append meta
                        if (hasMeta) {
                            dos.write(prevTuple.getFieldData(metaFieldIndex), prevTuple.getFieldStart(metaFieldIndex),
                                    prevTuple.getFieldLength(metaFieldIndex));
                            tb.addFieldEndOffset();
                        }
                        // if with filters, append the filter
                        if (isFiltered) {
                            dos.write(prevTuple.getFieldData(filterFieldIndex),
                                    prevTuple.getFieldStart(filterFieldIndex),
                                    prevTuple.getFieldLength(filterFieldIndex));
                            tb.addFieldEndOffset();
                        }
                        if (isNull(tuple, numOfPrimaryKeys)) {
                            // Only delete if it is a delete and not upsert
                            abstractModCallback.setOp(Operation.DELETE);
                            if (firstModification) {
                                lsmAccessor.delete(prevTuple);
                                firstModification = false;
                            } else {
                                lsmAccessor.forceDelete(prevTuple);
                            }
                        }
                    } else {
                        appendNullPreviousTuple();
                    }
                } else {
                    searchCallback.before(key);
                    appendNullPreviousTuple();
                }
                if (!isNull(tuple, numOfPrimaryKeys)) {
                    abstractModCallback.setOp(Operation.UPSERT);
                    if (firstModification) {
                        lsmAccessor.upsert(tuple);
                        firstModification = false;
                    } else {
                        lsmAccessor.forceUpsert(tuple);
                    }
                    recordWasInserted = true;
                }
                writeOutput(i, recordWasInserted, prevTuple != null);
                i++;
            }
            // callback here before calling nextFrame on the next operator
            frameOpCallback.frameCompleted(!firstModification);
            appender.write(writer, true);
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
    }

    private void appendNullPreviousTuple() throws IOException {
        prevTuple = null;
        addNullField();
        if (hasMeta) {
            addNullField();
        }
        // if with filters, append null
        if (isFiltered) {
            addNullField();
        }
        cursor.reset();
    }

    /**
     * Flushes tuples (which have already been written to tuple appender's buffer in writeOutput() method)
     * to the next operator/consumer.
     */
    @Override
    public void flushPartialFrame() throws HyracksDataException {
        appender.write(writer, true);
    }

    private ITupleReference getPrevTupleWithFilter(ITupleReference prevTuple) throws IOException, AsterixException {
        prevRecWithPKWithFilterValue.reset();
        for (int i = 0; i < prevTuple.getFieldCount(); i++) {
            prevDos.write(prevTuple.getFieldData(i), prevTuple.getFieldStart(i), prevTuple.getFieldLength(i));
            prevRecWithPKWithFilterValue.addFieldEndOffset();
        }
        recPointable.set(prevTuple.getFieldData(numOfPrimaryKeys), prevTuple.getFieldStart(numOfPrimaryKeys),
                prevTuple.getFieldLength(numOfPrimaryKeys));
        // copy the field data from prevTuple
        byte tag = recPointable.getClosedFieldType(recordType, presetFieldIndex).getTypeTag().serialize();
        prevDos.write(tag);
        prevDos.write(recPointable.getByteArray(), recPointable.getClosedFieldOffset(recordType, presetFieldIndex),
                recPointable.getClosedFieldSize(recordType, presetFieldIndex));
        prevRecWithPKWithFilterValue.addFieldEndOffset();
        // prepare the tuple
        prevTupleWithFilter.reset(prevRecWithPKWithFilterValue.getFieldEndOffsets(),
                prevRecWithPKWithFilterValue.getByteArray());
        return prevTupleWithFilter;
    }

    private RangePredicate createSearchPredicate() {
        keySearchCmp = BTreeUtils.getSearchMultiComparator(((ITreeIndex) index).getComparatorFactories(), key);
        return new RangePredicate(key, key, true, true, keySearchCmp, keySearchCmp, null, null);
    }

    @Override
    public void close() throws HyracksDataException {
        try {
            try {
                cursor.close();
            } finally {
                writer.close();
            }
        } finally {
            indexHelper.close();
        }
    }

    @Override
    public void fail() throws HyracksDataException {
        writer.fail();
    }

    @Override
    public void flush() throws HyracksDataException {
        // No op since nextFrame flushes by default
    }
}
