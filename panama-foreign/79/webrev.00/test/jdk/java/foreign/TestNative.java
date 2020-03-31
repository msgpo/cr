/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */

/*
 * @test
 * @modules java.base/jdk.internal.misc
 *          jdk.incubator.foreign/jdk.incubator.foreign.unsafe
 * @run testng/othervm -Djdk.incubator.foreign.Foreign=permit TestNative
 */

import jdk.incubator.foreign.Foreign;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemoryLayout.PathElement;
import jdk.incubator.foreign.MemoryLayouts;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SequenceLayout;
import jdk.internal.misc.Unsafe;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.invoke.VarHandle;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.testng.Assert.assertEquals;

public class TestNative {

    static Unsafe UNSAFE;

    static {
        UNSAFE = Unsafe.getUnsafe();
    }

    static SequenceLayout bytes = MemoryLayout.ofSequence(100,
            MemoryLayouts.JAVA_BYTE.withOrder(ByteOrder.nativeOrder())
    );

    static SequenceLayout chars = MemoryLayout.ofSequence(100,
            MemoryLayouts.JAVA_CHAR.withOrder(ByteOrder.nativeOrder())
    );

    static SequenceLayout shorts = MemoryLayout.ofSequence(100,
            MemoryLayouts.JAVA_SHORT.withOrder(ByteOrder.nativeOrder())
    );

    static SequenceLayout ints = MemoryLayout.ofSequence(100,
            MemoryLayouts.JAVA_INT.withOrder(ByteOrder.nativeOrder())
    );

    static SequenceLayout floats = MemoryLayout.ofSequence(100,
            MemoryLayouts.JAVA_FLOAT.withOrder(ByteOrder.nativeOrder())
    );

    static SequenceLayout longs = MemoryLayout.ofSequence(100,
            MemoryLayouts.JAVA_LONG.withOrder(ByteOrder.nativeOrder())
    );

    static SequenceLayout doubles = MemoryLayout.ofSequence(100,
            MemoryLayouts.JAVA_DOUBLE.withOrder(ByteOrder.nativeOrder())
    );

    static VarHandle byteHandle = bytes.varHandle(byte.class, PathElement.sequenceElement());
    static VarHandle charHandle = chars.varHandle(char.class, PathElement.sequenceElement());
    static VarHandle shortHandle = shorts.varHandle(short.class, PathElement.sequenceElement());
    static VarHandle intHandle = ints.varHandle(int.class, PathElement.sequenceElement());
    static VarHandle floatHandle = floats.varHandle(float.class, PathElement.sequenceElement());
    static VarHandle longHandle = doubles.varHandle(long.class, PathElement.sequenceElement());
    static VarHandle doubleHandle = longs.varHandle(double.class, PathElement.sequenceElement());

    static void initBytes(MemoryAddress base, SequenceLayout seq, BiConsumer<MemoryAddress, Long> handleSetter) {
        for (long i = 0; i < seq.elementCount().getAsLong() ; i++) {
            handleSetter.accept(base, i);
        }
    }

    static <Z extends Buffer> void checkBytes(MemoryAddress base, SequenceLayout layout,
                                              BiFunction<MemoryAddress, Long, Object> handleExtractor,
                                              Function<ByteBuffer, Z> bufferFactory,
                                              BiFunction<Z, Integer, Object> nativeBufferExtractor,
                                              BiFunction<Long, Integer, Object> nativeRawExtractor) {
        long nelems = layout.elementCount().getAsLong();
        ByteBuffer bb = base.segment().asSlice(base.offset(), (int)layout.byteSize()).asByteBuffer();
        Z z = bufferFactory.apply(bb);
        for (long i = 0 ; i < nelems ; i++) {
            Object handleValue = handleExtractor.apply(base, i);
            Object bufferValue = nativeBufferExtractor.apply(z, (int)i);
            Object rawValue = nativeRawExtractor.apply(Foreign.getInstance().asLong(base), (int)i);
            if (handleValue instanceof Number) {
                assertEquals(((Number)handleValue).longValue(), i);
                assertEquals(((Number)bufferValue).longValue(), i);
                assertEquals(((Number)rawValue).longValue(), i);
            } else {
                assertEquals((long)(char)handleValue, i);
                assertEquals((long)(char)bufferValue, i);
                assertEquals((long)(char)rawValue, i);
            }
        }
    }

    public static native byte getByteBuffer(ByteBuffer buf, int index);
    public static native char getCharBuffer(CharBuffer buf, int index);
    public static native short getShortBuffer(ShortBuffer buf, int index);
    public static native int getIntBuffer(IntBuffer buf, int index);
    public static native float getFloatBuffer(FloatBuffer buf, int index);
    public static native long getLongBuffer(LongBuffer buf, int index);
    public static native double getDoubleBuffer(DoubleBuffer buf, int index);

    public static native byte getByteRaw(long addr, int index);
    public static native char getCharRaw(long addr, int index);
    public static native short getShortRaw(long addr, int index);
    public static native int getIntRaw(long addr, int index);
    public static native float getFloatRaw(long addr, int index);
    public static native long getLongRaw(long addr, int index);
    public static native double getDoubleRaw(long addr, int index);

    public static native long getCapacity(Buffer buffer);

    @Test(dataProvider="nativeAccessOps")
    public void testNativeAccess(Consumer<MemoryAddress> checker, Consumer<MemoryAddress> initializer, SequenceLayout seq) {
        try (MemorySegment segment = MemorySegment.allocateNative(seq)) {
            MemoryAddress address = segment.baseAddress();
            initializer.accept(address);
            checker.accept(address);
        }
    }

    @Test(dataProvider="buffers")
    public void testNativeCapacity(Function<ByteBuffer, Buffer> bufferFunction, int elemSize) {
        int capacity = (int)doubles.byteSize();
        try (MemorySegment segment = MemorySegment.allocateNative(doubles)) {
            ByteBuffer bb = segment.asByteBuffer();
            Buffer buf = bufferFunction.apply(bb);
            int expected = capacity / elemSize;
            assertEquals(buf.capacity(), expected);
            assertEquals(getCapacity(buf), expected);
        }
    }

    static {
        System.loadLibrary("NativeAccess");
    }

    @DataProvider(name = "nativeAccessOps")
    public Object[][] nativeAccessOps() {
        Consumer<MemoryAddress> byteInitializer =
                (base) -> initBytes(base, bytes, (addr, pos) -> byteHandle.set(addr, pos, (byte)(long)pos));
        Consumer<MemoryAddress> charInitializer =
                (base) -> initBytes(base, chars, (addr, pos) -> charHandle.set(addr, pos, (char)(long)pos));
        Consumer<MemoryAddress> shortInitializer =
                (base) -> initBytes(base, shorts, (addr, pos) -> shortHandle.set(addr, pos, (short)(long)pos));
        Consumer<MemoryAddress> intInitializer =
                (base) -> initBytes(base, ints, (addr, pos) -> intHandle.set(addr, pos, (int)(long)pos));
        Consumer<MemoryAddress> floatInitializer =
                (base) -> initBytes(base, floats, (addr, pos) -> floatHandle.set(addr, pos, (float)(long)pos));
        Consumer<MemoryAddress> longInitializer =
                (base) -> initBytes(base, longs, (addr, pos) -> longHandle.set(addr, pos, (long)pos));
        Consumer<MemoryAddress> doubleInitializer =
                (base) -> initBytes(base, doubles, (addr, pos) -> doubleHandle.set(addr, pos, (double)(long)pos));

        Consumer<MemoryAddress> byteChecker =
                (base) -> checkBytes(base, bytes, byteHandle::get, bb -> bb, TestNative::getByteBuffer, TestNative::getByteRaw);
        Consumer<MemoryAddress> charChecker =
                (base) -> checkBytes(base, chars, charHandle::get, ByteBuffer::asCharBuffer, TestNative::getCharBuffer, TestNative::getCharRaw);
        Consumer<MemoryAddress> shortChecker =
                (base) -> checkBytes(base, shorts, shortHandle::get, ByteBuffer::asShortBuffer, TestNative::getShortBuffer, TestNative::getShortRaw);
        Consumer<MemoryAddress> intChecker =
                (base) -> checkBytes(base, ints, intHandle::get, ByteBuffer::asIntBuffer, TestNative::getIntBuffer, TestNative::getIntRaw);
        Consumer<MemoryAddress> floatChecker =
                (base) -> checkBytes(base, floats, floatHandle::get, ByteBuffer::asFloatBuffer, TestNative::getFloatBuffer, TestNative::getFloatRaw);
        Consumer<MemoryAddress> longChecker =
                (base) -> checkBytes(base, longs, longHandle::get, ByteBuffer::asLongBuffer, TestNative::getLongBuffer, TestNative::getLongRaw);
        Consumer<MemoryAddress> doubleChecker =
                (base) -> checkBytes(base, doubles, doubleHandle::get, ByteBuffer::asDoubleBuffer, TestNative::getDoubleBuffer, TestNative::getDoubleRaw);

        return new Object[][]{
                {byteChecker, byteInitializer, bytes},
                {charChecker, charInitializer, chars},
                {shortChecker, shortInitializer, shorts},
                {intChecker, intInitializer, ints},
                {floatChecker, floatInitializer, floats},
                {longChecker, longInitializer, longs},
                {doubleChecker, doubleInitializer, doubles}
        };
    }

    @DataProvider(name = "buffers")
    public Object[][] buffers() {
        return new Object[][] {
                { (Function<ByteBuffer, Buffer>)bb -> bb, 1 },
                { (Function<ByteBuffer, Buffer>)ByteBuffer::asCharBuffer, 2 },
                { (Function<ByteBuffer, Buffer>)ByteBuffer::asShortBuffer, 2 },
                { (Function<ByteBuffer, Buffer>)ByteBuffer::asIntBuffer, 4 },
                { (Function<ByteBuffer, Buffer>)ByteBuffer::asFloatBuffer, 4 },
                { (Function<ByteBuffer, Buffer>)ByteBuffer::asLongBuffer, 8 },
                { (Function<ByteBuffer, Buffer>)ByteBuffer::asDoubleBuffer, 8 },
        };
    }
}
