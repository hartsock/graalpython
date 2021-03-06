/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.objects.str;

import static com.oracle.graal.python.nodes.PGuards.cannotBeOverridden;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.MemoryError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.ValueError;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.cext.PythonNativeObject;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.PCallCapiFunction;
import com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.ToSulongNode;
import com.oracle.graal.python.builtins.objects.cext.capi.NativeCAPISymbol;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.UnicodeFromWcharNode;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.builtins.objects.object.PythonObjectLibrary;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.PNodeWithContext;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.attributes.ReadAttributeFromDynamicObjectNode;
import com.oracle.graal.python.nodes.attributes.WriteAttributeToDynamicObjectNode;
import com.oracle.graal.python.nodes.classes.IsSubtypeNode;
import com.oracle.graal.python.nodes.control.GetNextNode;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.nodes.util.CannotCastException;
import com.oracle.graal.python.nodes.util.CastToJavaIntExactNode;
import com.oracle.graal.python.nodes.util.CastToJavaStringNode;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.graal.python.runtime.sequence.PSequence;
import com.oracle.graal.python.runtime.sequence.storage.SequenceStorage;
import com.oracle.graal.python.util.OverflowException;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

public abstract class StringNodes {

    public static boolean isNativeCharSequence(PString x) {
        return x.getCharSequence() instanceof NativeCharSequence;
    }

    public static boolean isNativeMaterialized(PString seq) {
        return ((NativeCharSequence) seq.getCharSequence()).isMaterialized();
    }

    public static boolean isLazyCharSequence(PString x) {
        return x.getCharSequence() instanceof LazyString;
    }

    public static boolean isMaterialized(PString x) {
        return x.getCharSequence() instanceof String;
    }

    @GenerateUncached
    @ImportStatic(StringNodes.class)
    public abstract static class StringMaterializeNode extends Node {

        public abstract String execute(PString materialize);

        @Specialization(guards = {"isNativeCharSequence(x)", "isNativeMaterialized(x)"})
        static String doMaterializedNative(PString x) {
            return ((NativeCharSequence) x.getCharSequence()).getMaterialized();
        }

        @Specialization(guards = {"isNativeCharSequence(x)"}, replaces = "doMaterializedNative")
        static String doNative(PString x,
                        @Cached PCallCapiFunction callCStringToStringNode,
                        @Cached UnicodeFromWcharNode fromWcharNode) {
            // cast guaranteed by the guard
            String materialized = materializeNativeCharSequence((NativeCharSequence) x.getCharSequence(), callCStringToStringNode, fromWcharNode);
            x.setCharSequence(materialized);
            return materialized;
        }

        @Specialization(guards = "isLazyCharSequence(x)")
        static String doLazyString(PString x) {
            // cast guaranteed by the guard
            String materialized = ((LazyString) x.getCharSequence()).materialize();
            x.setCharSequence(materialized);
            return materialized;
        }

        @Specialization(guards = "isMaterialized(x)")
        static String doMaterialized(PString x) {
            // cast guaranteed by the guard
            return (String) x.getCharSequence();
        }

        public static String materializeNativeCharSequence(NativeCharSequence nativeCharSequence,
                        PCallCapiFunction callCStringToStringNode,
                        UnicodeFromWcharNode fromWcharNode) {
            // cast guaranteed by the guard
            String materialized;
            if (nativeCharSequence.isAsciiOnly()) {
                materialized = (String) callCStringToStringNode.call(NativeCAPISymbol.FUN_PY_TRUFFLE_ASCII_TO_STRING, nativeCharSequence.getPtr());
            } else {
                switch (nativeCharSequence.getElementSize()) {
                    case 1:
                        materialized = (String) callCStringToStringNode.call(NativeCAPISymbol.FUN_PY_TRUFFLE_CSTR_TO_STRING, nativeCharSequence.getPtr());
                        break;
                    case 2:
                    case 4:
                        materialized = fromWcharNode.execute(nativeCharSequence.getPtr(), nativeCharSequence.getElementSize());
                        break;
                    default:
                        throw CompilerDirectives.shouldNotReachHere("illegal element size");
                }
            }
            return materialized;
        }
    }

    @GenerateUncached
    @ImportStatic(StringNodes.class)
    public abstract static class StringLenNode extends Node {

        public abstract int execute(Object str);

        @Specialization
        static int doString(String str) {
            return str.length();
        }

        @Specialization(guards = "isMaterialized(x)")
        static int doMaterialized(PString x) {
            // cast guaranteed by the guard
            return CompilerDirectives.castExact(x.getCharSequence(), String.class).length();
        }

        @Specialization(guards = "isNativeCharSequence(x)")
        static int doNativeCharSequence(PString x, @Cached StringMaterializeNode materializeNode) {
            return materializeNode.execute(x).length();
        }

        @Specialization(guards = "isLazyCharSequence(x)")
        static int doLazyString(PString x) {
            // cast guaranteed by the guard
            return CompilerDirectives.castExact(x.getCharSequence(), LazyString.class).length();
        }

        @Specialization(guards = {"isNativeCharSequence(x)", "isNativeMaterialized(x)"})
        static int nativeString(PString x) {
            // cast guaranteed by the guard
            return CompilerDirectives.castExact(x.getCharSequence(), NativeCharSequence.class).getMaterialized().length();
        }

        @Specialization(guards = {"isNativeCharSequence(x)", "!isNativeMaterialized(x)"}, replaces = "nativeString", limit = "3")
        static int nativeStringMat(@SuppressWarnings("unused") PString x,
                        @Bind("getNativeCharSequence(x)") NativeCharSequence ncs,
                        @CachedLibrary("ncs") InteropLibrary lib,
                        @Cached CastToJavaIntExactNode castToJavaIntNode) {
            return ncs.length(lib, castToJavaIntNode);
        }

        @Specialization(limit = "2")
        static int doNativeObject(PythonNativeObject x,
                        @CachedLibrary("x") PythonObjectLibrary plib,
                        @Cached IsSubtypeNode isSubtypeNode,
                        @Cached PCallCapiFunction callNativeUnicodeAsStringNode,
                        @Cached ToSulongNode toSulongNode,
                        @Cached PRaiseNode raiseNode) {
            if (isSubtypeNode.execute(plib.getLazyPythonClass(x), PythonBuiltinClassType.PString)) {
                // read the native data
                Object result = callNativeUnicodeAsStringNode.call(NativeCAPISymbol.FUN_PY_UNICODE_GET_LENGTH, toSulongNode.execute(x));
                assert result instanceof Number;
                return intValue((Number) result);
            }
            // the object's type is not a subclass of 'str'
            throw raiseNode.raise(PythonBuiltinClassType.TypeError, ErrorMessages.BAD_ARG_TYPE_FOR_BUILTIN_OP);
        }

        @TruffleBoundary
        private static int intValue(Number result) {
            return result.intValue();
        }

        static NativeCharSequence getNativeCharSequence(PString self) {
            return (NativeCharSequence) self.getCharSequence();
        }
    }

    public abstract static class CastToJavaStringCheckedNode extends Node {
        public final String cast(Object object, String errMsgFormat, Object... errMsgArgs) {
            return execute(object, errMsgFormat, errMsgArgs);
        }

        public abstract String execute(Object object, String errMsgFormat, Object[] errMsgArgs);

        @Specialization
        static String doConvert(Object self, String errMsgFormat, Object[] errMsgArgs,
                        @Cached CastToJavaStringNode castToJavaStringNode,
                        @Cached BranchProfile errorBranch,
                        @Cached PRaiseNode raiseNode) {
            try {
                return castToJavaStringNode.execute(self);
            } catch (CannotCastException e) {
                errorBranch.enter();
                throw raiseNode.raise(PythonBuiltinClassType.TypeError, errMsgFormat, errMsgArgs);
            }
        }

        public static CastToJavaStringCheckedNode create() {
            return StringNodesFactory.CastToJavaStringCheckedNodeGen.create();
        }
    }

    @ImportStatic({PGuards.class, PythonOptions.class})
    public abstract static class JoinInternalNode extends PNodeWithContext {

        public static final String INVALID_SEQ_ITEM = "sequence item %d: expected str instance, %p found";

        public abstract String execute(VirtualFrame frame, String self, Object iterable);

        @Specialization
        static String doString(String self, String arg) {
            if (arg.isEmpty()) {
                return "";
            }
            return joinString(self, arg);
        }

        @TruffleBoundary
        private static String joinString(String self, String arg) {
            StringBuilder sb = new StringBuilder();
            char[] joinString = arg.toCharArray();
            assert joinString.length > 0;
            for (int i = 0; i < joinString.length - 1; i++) {
                sb.append(joinString[i]);
                sb.append(self);
            }
            sb.append(joinString[joinString.length - 1]);
            return sb.toString();
        }

        // This specialization is just for better interpreter performance.
        // IMPORTANT: only do this if the sequence is exactly list or tuple (not subclassed); for
        // semantics, see CPython's 'abstract.c' function 'PySequence_Fast'
        @Specialization(guards = "isExactlyListOrTuple(plib, tupleProfile, listProfile, sequence)")
        static String doPSequence(VirtualFrame frame, String self, PSequence sequence,
                        @SuppressWarnings("unused") @CachedLibrary(limit = "3") PythonObjectLibrary plib,
                        @Cached @SuppressWarnings("unused") IsBuiltinClassProfile tupleProfile,
                        @Cached @SuppressWarnings("unused") IsBuiltinClassProfile listProfile,
                        @Cached SequenceNodes.GetSequenceStorageNode getSequenceStorageNode,
                        @Cached SequenceStorageNodes.LenNode lenNode,
                        @Cached ConditionProfile isEmptyProfile,
                        @Cached ConditionProfile isSingleItemProfile,
                        @Cached SequenceStorageNodes.GetItemNode getItemNode,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode,
                        @Cached PRaiseNode raise) {

            SequenceStorage storage = getSequenceStorageNode.execute(sequence);
            int len = lenNode.execute(storage);

            // shortcut
            if (isEmptyProfile.profile(len == 0)) {
                return "";
            }

            StringBuilder sb = PythonUtils.newStringBuilder();
            int i = 0;

            try {
                // manually peel first iteration
                Object item = getItemNode.execute(frame, storage, i);
                // shortcut
                if (isSingleItemProfile.profile(len == 1)) {
                    return castToJavaStringNode.cast(item, INVALID_SEQ_ITEM, i, item);
                }
                PythonUtils.append(sb, castToJavaStringNode.cast(item, INVALID_SEQ_ITEM, i, item));

                for (i = 1; i < len; i++) {
                    PythonUtils.append(sb, self);
                    item = getItemNode.execute(frame, storage, i);
                    PythonUtils.append(sb, castToJavaStringNode.cast(item, INVALID_SEQ_ITEM, i, item));
                }
                return PythonUtils.sbToString(sb);
            } catch (OutOfMemoryError e) {
                throw raise.raise(MemoryError);
            }
        }

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        static String doGeneric(VirtualFrame frame, String string, Object iterable,
                        @Cached PRaiseNode raise,
                        @CachedLibrary("iterable") PythonObjectLibrary lib,
                        @Cached GetNextNode nextNode,
                        @Cached IsBuiltinClassProfile errorProfile0,
                        @Cached IsBuiltinClassProfile errorProfile1,
                        @Cached IsBuiltinClassProfile errorProfile2,
                        @Cached CastToJavaStringNode castStrNode) {
            Object iterator;
            try {
                iterator = lib.getIteratorWithFrame(iterable, frame);
            } catch (PException e) {
                e.expect(PythonBuiltinClassType.TypeError, errorProfile0);
                throw raise.raise(PythonBuiltinClassType.TypeError, ErrorMessages.CAN_ONLY_JOIN_ITERABLE);
            }
            try {
                StringBuilder str = PythonUtils.newStringBuilder();
                try {
                    PythonUtils.append(str, checkItem(nextNode.execute(frame, iterator), 0, castStrNode, raise));
                } catch (PException e) {
                    e.expectStopIteration(errorProfile1);
                    return "";
                }
                int i = 1;
                while (true) {
                    Object value;
                    try {
                        value = nextNode.execute(frame, iterator);
                    } catch (PException e) {
                        e.expectStopIteration(errorProfile2);
                        return PythonUtils.sbToString(str);
                    }
                    PythonUtils.append(str, string);
                    PythonUtils.append(str, checkItem(value, i++, castStrNode, raise));
                }
            } catch (OutOfMemoryError e) {
                throw raise.raise(MemoryError);
            }
        }

        private static String checkItem(Object item, int pos, CastToJavaStringNode castNode, PRaiseNode raise) {
            try {
                return castNode.execute(item);
            } catch (CannotCastException e) {
                throw raise.raise(TypeError, INVALID_SEQ_ITEM, pos, item);
            }
        }

        static boolean isExactlyListOrTuple(PythonObjectLibrary lib, IsBuiltinClassProfile tupleProfile, IsBuiltinClassProfile listProfile, PSequence sequence) {
            Object cls = lib.getLazyPythonClass(sequence);
            return tupleProfile.profileClass(cls, PythonBuiltinClassType.PTuple) || listProfile.profileClass(cls, PythonBuiltinClassType.PList);
        }
    }

    @ImportStatic(PGuards.class)
    public abstract static class SpliceNode extends PNodeWithContext {

        public abstract void execute(StringBuilder sb, Object translated);

        @Specialization(guards = "isNone(none)")
        @SuppressWarnings("unused")
        static void doNone(StringBuilder sb, PNone none) {
        }

        @Specialization
        @TruffleBoundary(allowInlining = true)
        static void doInt(StringBuilder sb, int translated,
                        @Shared("raise") @Cached PRaiseNode raise) {
            if (Character.isValidCodePoint(translated)) {
                sb.appendCodePoint(translated);
            } else {
                throw raise.raise(ValueError, "invalid unicode code poiont");
            }
        }

        @Specialization
        static void doLong(StringBuilder sb, long translated,
                        @Shared("raise") @Cached PRaiseNode raise,
                        @Shared("overflow") @Cached BranchProfile ovf) {
            try {
                doInt(sb, PInt.intValueExact(translated), raise);
            } catch (OverflowException e) {
                ovf.enter();
                throw raiseError(raise);
            }
        }

        @Specialization
        static void doPInt(StringBuilder sb, PInt translated,
                        @Shared("raise") @Cached PRaiseNode raise,
                        @Shared("overflow") @Cached BranchProfile ovf) {
            try {
                doInt(sb, translated.intValueExact(), raise);
            } catch (OverflowException e) {
                ovf.enter();
                throw raiseError(raise);
            }
        }

        @Specialization
        @TruffleBoundary(allowInlining = true)
        static void doString(StringBuilder sb, String translated) {
            sb.append(translated);
        }

        @Specialization(guards = {"!isInteger(translated)", "!isPInt(translated)", "!isNone(translated)"})
        static void doObject(StringBuilder sb, Object translated,
                        @Shared("raise") @Cached PRaiseNode raise,
                        @Cached CastToJavaStringNode castToJavaStringNode) {

            try {
                String translatedStr = castToJavaStringNode.execute(translated);
                doString(sb, translatedStr);
            } catch (CannotCastException e) {
                throw raise.raise(PythonBuiltinClassType.TypeError, ErrorMessages.CHARACTER_MAPPING_MUST_RETURN_INT_NONE_OR_STR);
            }
        }

        private static PException raiseError(PRaiseNode raise) {
            return raise.raise(ValueError, ErrorMessages.CHARACTER_MAPPING_MUST_BE_IN_RANGE, PInt.toHexString(Character.MAX_CODE_POINT + 1));
        }

    }

    public abstract static class FindNode extends PNodeWithContext {

        public abstract int execute(String self, String sub, int start, int end);

        @Specialization
        int find(String haystack, String needle, int start, int end) {
            int len1 = haystack.length();
            int len2 = needle.length();

            if (len2 == 0 && start <= len1) {
                return emptySubIndex(start, end);
            }
            if (start >= len1 || len1 < len2) {
                return -1;
            }

            return findWithBounds(haystack, needle, start, end > len1 ? len1 : end);
        }

        // Overridden in RFind
        @SuppressWarnings("unused")
        protected int emptySubIndex(int start, int end) {
            return start;
        }

        protected int findWithBounds(String haystack, String needle, int start, int end) {
            int idx = PString.indexOf(haystack, needle, start);
            return idx + needle.length() <= end ? idx : -1;
        }

        public static FindNode create() {
            return StringNodesFactory.FindNodeGen.create();
        }
    }

    public abstract static class RFindNode extends FindNode {

        @Override
        protected int emptySubIndex(int start, int end) {
            return (end - start) + start;
        }

        @Override
        protected int findWithBounds(String haystack, String needle, int start, int end) {
            int idx = PString.lastIndexOf(haystack, needle, end - needle.length());
            return idx >= start ? idx : -1;
        }

        public static RFindNode create() {
            return StringNodesFactory.RFindNodeGen.create();
        }
    }

    public abstract static class CountNode extends PNodeWithContext {

        public abstract int execute(String self, String sub, int start, int end);

        protected boolean stringIsEmpty(String s) {
            return s.length() == 0;
        }

        protected boolean noStringIsEmpty(String a, String b) {
            return !stringIsEmpty(a) && !stringIsEmpty(b);
        }

        @Specialization(guards = "stringIsEmpty(self)")
        int countSelfEmpty(@SuppressWarnings("unused") String self, String sub, int start, @SuppressWarnings("unused") int end) {
            return (sub.length() == 0 && start <= 0) ? 1 : 0;
        }

        @Specialization(guards = "stringIsEmpty(sub)")
        int countSubEmpty(String self, @SuppressWarnings("unused") String sub, int start, int end) {
            return (start <= self.length()) ? (end - start) + 1 : 0;
        }

        @Specialization(guards = "noStringIsEmpty(self, sub)")
        int count(String self, String sub, int start, int end,
                        @Cached FindNode findNode) {
            int selfLen = self.length();
            int subLen = sub.length();

            int idx = findNode.execute(self, sub, start, end);
            if (idx < 0) {
                return 0;
            }

            int cnt = 1;
            while (idx < selfLen && idx >= 0 && cnt < selfLen) {
                idx = findNode.execute(self, sub, idx + subLen, end);
                if (idx >= 0) {
                    cnt++;
                }
            }
            return cnt;
        }

        public static CountNode create() {
            return StringNodesFactory.CountNodeGen.create();
        }
    }

    @ImportStatic(PGuards.class)
    @GenerateUncached
    public abstract static class InternStringNode extends Node {
        public abstract PString execute(Object string);

        @Specialization
        static PString doString(String string,
                        @Shared("writeNode") @Cached WriteAttributeToDynamicObjectNode writeNode,
                        @Cached PythonObjectFactory factory) {
            final PString interned = factory.createString(string);
            writeNode.execute(interned, PString.INTERNED, true);
            return interned;
        }

        @Specialization(limit = "1")
        static PString doPString(PString string,
                        @CachedLibrary("string") PythonObjectLibrary lib,
                        @Shared("writeNode") @Cached WriteAttributeToDynamicObjectNode writeNode) {
            if (cannotBeOverridden(lib.getLazyPythonClass(string))) {
                writeNode.execute(string, PString.INTERNED, true);
                return string;
            }
            return null;
        }

        @Fallback
        static PString doOthers(@SuppressWarnings("unused") Object string) {
            return null;
        }

        public static InternStringNode create() {
            return StringNodesFactory.InternStringNodeGen.create();
        }
    }

    @ImportStatic(PGuards.class)
    @GenerateUncached
    public abstract static class IsInternedStringNode extends Node {
        public abstract boolean execute(PString string);

        @Specialization
        boolean doIt(PString string,
                        @Cached ReadAttributeFromDynamicObjectNode readNode) {
            final Object isInterned = readNode.execute(string, PString.INTERNED);
            return isInterned instanceof Boolean && (boolean) isInterned;
        }
    }
}
