/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.internal.foreign.abi.x64.windows;

import jdk.incubator.foreign.ForeignLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.foreign.abi.UpcallStubs;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

import static jdk.incubator.foreign.C.*;

/**
 * ABI implementation based on Windows ABI AMD64 supplement v.0.99.6
 */
public class Windowsx64Linker implements ForeignLinker {

    public static final int MAX_INTEGER_ARGUMENT_REGISTERS = 4;
    public static final int MAX_INTEGER_RETURN_REGISTERS = 1;
    public static final int MAX_VECTOR_ARGUMENT_REGISTERS = 4;
    public static final int MAX_VECTOR_RETURN_REGISTERS = 1;
    public static final int MAX_REGISTER_ARGUMENTS = 4;
    public static final int MAX_REGISTER_RETURNS = 1;

    private static Windowsx64Linker instance;

    static final long ADDRESS_SIZE = 64; // bits

    public static Windowsx64Linker getInstance() {
        if (instance == null) {
            instance = new Windowsx64Linker();
        }
        return instance;
    }

    @Override
    public MethodHandle downcallHandle(MemoryAddress symbol, MethodType type, FunctionDescriptor function) {
        return CallArranger.arrangeDowncall(symbol, type, function);
    }

    @Override
    public MemorySegment upcallStub(MethodHandle target, FunctionDescriptor function) {
        return UpcallStubs.upcallAddress(CallArranger.arrangeUpcall(target, target.type(), function));
    }

    @Override
    public String name() {
        return Win64.NAME;
    }

    static Win64.ArgumentClass argumentClassFor(MemoryLayout layout) {
        return (Win64.ArgumentClass)layout.attribute(Win64.CLASS_ATTRIBUTE_NAME).get();
    }
}
