/*******************************************************************************

 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.drill.exec.vector.complex;

import com.google.common.collect.ObjectArrays;
import io.netty.buffer.DrillBuf;
import org.apache.drill.common.expression.FieldReference;
import org.apache.drill.common.expression.PathSegment;
import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.common.types.Types;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.exception.OutOfMemoryException;
import org.apache.drill.exec.proto.UserBitShared;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.TransferPair;
import org.apache.drill.exec.record.TypedFieldId;
import org.apache.drill.exec.util.CallBack;
import org.apache.drill.exec.util.JsonStringArrayList;
import org.apache.drill.exec.vector.AddOrGetResult;
import org.apache.drill.exec.vector.UInt1Vector;
import org.apache.drill.exec.vector.UInt4Vector;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.VectorDescriptor;
import org.apache.drill.exec.vector.ZeroVector;
import org.apache.drill.exec.vector.complex.impl.ComplexCopier;
import org.apache.drill.exec.vector.complex.impl.UnionListReader;
import org.apache.drill.exec.vector.complex.impl.UnionListWriter;
import org.apache.drill.exec.vector.complex.reader.FieldReader;
import org.apache.drill.exec.vector.complex.writer.FieldWriter;

import java.util.List;

public class ListVector extends BaseRepeatedValueVector {

  private UInt4Vector offsets;
  private final UInt1Vector bits;
  private Mutator mutator = new Mutator();
  private Accessor accessor = new Accessor();
  private UnionListWriter writer;
  private UnionListReader reader;
  private CallBack callBack;

  public ListVector(MaterializedField field, BufferAllocator allocator, CallBack callBack) {
    super(field, allocator);
    this.bits = new UInt1Vector(MaterializedField.create("$bits$", Types.required(MinorType.UINT1)), allocator);
    offsets = getOffsetVector();
    this.field.addChild(getDataVector().getField());
    this.writer = new UnionListWriter(this);
    this.reader = new UnionListReader(this);
    this.callBack = callBack;
  }

  public UnionListWriter getWriter() {
    return writer;
  }

  @Override
  public void allocateNew() throws OutOfMemoryException {
    super.allocateNewSafe();
  }

  public void transferTo(ListVector target) {
    offsets.makeTransferPair(target.offsets).transfer();
    bits.makeTransferPair(target.bits).transfer();
    if (target.getDataVector() instanceof ZeroVector) {
      target.addOrGetVector(new VectorDescriptor(vector.getField().getType()));
    }
    getDataVector().makeTransferPair(target.getDataVector()).transfer();
  }

  public void copyFromSafe(int inIndex, int outIndex, ListVector from) {
    copyFrom(inIndex, outIndex, from);
  }

  public void copyFrom(int inIndex, int outIndex, ListVector from) {
    FieldReader in = from.getReader();
    in.setPosition(inIndex);
    FieldWriter out = getWriter();
    out.setPosition(outIndex);
    ComplexCopier.copy(in, out);
  }

  @Override
  public ValueVector getDataVector() {
    return vector;
  }

  @Override
  public TransferPair getTransferPair(FieldReference ref) {
    return new TransferImpl(field.withPath(ref));
  }

  @Override
  public TransferPair makeTransferPair(ValueVector target) {
    return new TransferImpl((ListVector) target);
  }

  private class TransferImpl implements TransferPair {

    ListVector to;

    public TransferImpl(MaterializedField field) {
      to = new ListVector(field, allocator, null);
      to.addOrGetVector(new VectorDescriptor(vector.getField().getType()));
    }

    public TransferImpl(ListVector to) {
      this.to = to;
      to.addOrGetVector(new VectorDescriptor(vector.getField().getType()));
    }

    @Override
    public void transfer() {
      transferTo(to);
    }

    @Override
    public void splitAndTransfer(int startIndex, int length) {
      to.allocateNew();
      for (int i = 0; i < length; i++) {
        copyValueSafe(startIndex + i, i);
      }
    }

    @Override
    public ValueVector getTo() {
      return to;
    }

    @Override
    public void copyValueSafe(int from, int to) {
      this.to.copyFrom(from, to, ListVector.this);
    }
  }

  @Override
  public Accessor getAccessor() {
    return accessor;
  }

  @Override
  public Mutator getMutator() {
    return mutator;
  }

  @Override
  public FieldReader getReader() {
    return reader;
  }

  @Override
  public boolean allocateNewSafe() {
    /* boolean to keep track if all the memory allocation were successful
     * Used in the case of composite vectors when we need to allocate multiple
     * buffers for multiple vectors. If one of the allocations failed we need to
     * clear all the memory that we allocated
     */
    boolean success = false;
    try {
      if (!offsets.allocateNewSafe()) {
        return false;
      }
      success = vector.allocateNewSafe();
      success = success && bits.allocateNewSafe();
    } finally {
      if (!success) {
        clear();
      }
    }
    if (success) {
      offsets.zeroVector();
      bits.zeroVector();
    }
    return success;
  }

  @Override
  protected UserBitShared.SerializedField.Builder getMetadataBuilder() {
    return getField().getAsBuilder()
            .setValueCount(getAccessor().getValueCount())
            .setBufferLength(getBufferSize())
            .addChild(offsets.getMetadata())
            .addChild(bits.getMetadata())
            .addChild(vector.getMetadata());
  }
  public <T extends ValueVector> AddOrGetResult<T> addOrGetVector(VectorDescriptor descriptor) {
    AddOrGetResult<T> result = super.addOrGetVector(descriptor);
    reader = new UnionListReader(this);
    return result;
  }

  @Override
  public int getBufferSize() {
    if (getAccessor().getValueCount() == 0) {
      return 0;
    }
    return offsets.getBufferSize() + bits.getBufferSize() + vector.getBufferSize();
  }

  @Override
  public void clear() {
    offsets.clear();
    vector.clear();
    bits.clear();
    lastSet = 0;
    super.clear();
  }

  @Override
  public DrillBuf[] getBuffers(boolean clear) {
    final DrillBuf[] buffers = ObjectArrays.concat(offsets.getBuffers(false), ObjectArrays.concat(bits.getBuffers(false),
            vector.getBuffers(false), DrillBuf.class), DrillBuf.class);
    if (clear) {
      for (DrillBuf buffer:buffers) {
        buffer.retain();
      }
      clear();
    }
    return buffers;
  }

  @Override
  public void load(UserBitShared.SerializedField metadata, DrillBuf buffer) {
    final UserBitShared.SerializedField offsetMetadata = metadata.getChild(0);
    offsets.load(offsetMetadata, buffer);

    final int offsetLength = offsetMetadata.getBufferLength();
    final UserBitShared.SerializedField bitMetadata = metadata.getChild(1);
    final int bitLength = bitMetadata.getBufferLength();
    bits.load(bitMetadata, buffer.slice(offsetLength, bitLength));

    final UserBitShared.SerializedField vectorMetadata = metadata.getChild(2);
    if (getDataVector() == DEFAULT_DATA_VECTOR) {
      addOrGetVector(VectorDescriptor.create(vectorMetadata.getMajorType()));
    }

    final int vectorLength = vectorMetadata.getBufferLength();
    vector.load(vectorMetadata, buffer.slice(offsetLength + bitLength, vectorLength));
  }

  public UnionVector promoteToUnion() {
    MaterializedField newField = MaterializedField.create(getField().getPath(), Types.optional(MinorType.UNION));
    UnionVector vector = new UnionVector(newField, allocator, null);
    replaceDataVector(vector);
    reader = new UnionListReader(this);
    return vector;
  }

  public TypedFieldId getFieldIdIfMatches(TypedFieldId.Builder builder, boolean addToBreadCrumb, PathSegment seg) {
    return FieldIdUtil.getFieldIdIfMatches(this, builder, addToBreadCrumb, seg);
  }

  private int lastSet;

  public class Accessor extends BaseRepeatedAccessor {

    @Override
    public Object getObject(int index) {
      if (isNull(index)) {
        return null;
      }
      final List<Object> vals = new JsonStringArrayList<>();
      final UInt4Vector.Accessor offsetsAccessor = offsets.getAccessor();
      final int start = offsetsAccessor.get(index);
      final int end = offsetsAccessor.get(index + 1);
      final ValueVector.Accessor valuesAccessor = getDataVector().getAccessor();
      for(int i = start; i < end; i++) {
        vals.add(valuesAccessor.getObject(i));
      }
      return vals;
    }

    @Override
    public boolean isNull(int index) {
      return bits.getAccessor().get(index) == 0;
    }
  }

  public class Mutator extends BaseRepeatedMutator {
    public void setNotNull(int index) {
      bits.getMutator().setSafe(index, 1);
      lastSet = index + 1;
    }

    @Override
    public void startNewValue(int index) {
      for (int i = lastSet; i <= index; i++) {
        offsets.getMutator().setSafe(i + 1, offsets.getAccessor().get(i));
      }
      setNotNull(index);
      lastSet = index + 1;
    }

    @Override
    public void setValueCount(int valueCount) {
      // TODO: populate offset end points
      if (valueCount == 0) {
        offsets.getMutator().setValueCount(0);
      } else {
        for (int i = lastSet; i < valueCount; i++) {
          offsets.getMutator().setSafe(i + 1, offsets.getAccessor().get(i));
        }
        offsets.getMutator().setValueCount(valueCount + 1);
      }
      final int childValueCount = valueCount == 0 ? 0 : offsets.getAccessor().get(valueCount);
      vector.getMutator().setValueCount(childValueCount);
      bits.getMutator().setValueCount(valueCount);
    }
  }
}
