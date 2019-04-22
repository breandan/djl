/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.apache.mxnet.jna;

import com.amazon.ai.util.PairList;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import org.apache.mxnet.Context;
import org.apache.mxnet.model.DataDesc;
import org.apache.mxnet.model.NdArray;
import org.apache.mxnet.model.ResourceAllocator;
import org.apache.mxnet.model.Shape;
import org.apache.mxnet.types.DataType;
import org.apache.mxnet.types.StorageType;

public final class JnaUtils {

    private static final String[] OP_NAME_PREFIX = {
        "_contrib_", "_linalg_", "_sparse_", "_image_", "_random_"
    };

    private JnaUtils() {}

    /////////////////////////////////
    // MXNet information
    /////////////////////////////////

    public static int getVersion(IntBuffer out) {
        IntBuffer version = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXGetVersion(version));

        return version.get();
    }

    public static Set<String> getAllOpNames() {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference outArray = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXListAllOpNames(outSize, outArray));

        int size = outSize.get();
        Pointer[] pointers = outArray.getValue().getPointerArray(0, size);

        Set<String> set = new HashSet<>();
        for (Pointer p : pointers) {
            set.add(p.getString(0));
        }
        return set;
    }

    public static Map<String, FunctionInfo> getNdArrayFunctions() {
        Set<String> opNames = JnaUtils.getAllOpNames();
        Map<String, FunctionInfo> map = new HashMap<>();

        for (String opName : opNames) {
            PointerByReference ref = new PointerByReference();
            checkCall(MxnetLibrary.INSTANCE.NNGetOpHandle(opName, ref));

            String functionName = getOpNamePrefix(opName);

            // System.out.println("Name: " + opName + "/" + functionName);
            map.put(functionName, getFunctionByName(opName, functionName, ref.getValue()));
        }
        return map;
    }

    public static FunctionInfo getFunctionByName(String name, String functionName, Pointer handle) {
        String[] nameRef = new String[] {name};
        String[] description = new String[1];
        IntBuffer numArgs = IntBuffer.allocate(1);
        PointerByReference argNameRef = new PointerByReference();
        PointerByReference argTypeRef = new PointerByReference();
        PointerByReference argDescRef = new PointerByReference();
        String[] keyVarArgs = new String[1];
        String[] returnType = new String[1];

        checkCall(
                MxnetLibrary.INSTANCE.MXSymbolGetAtomicSymbolInfo(
                        handle,
                        nameRef,
                        description,
                        numArgs,
                        argNameRef,
                        argTypeRef,
                        argDescRef,
                        keyVarArgs,
                        returnType));

        int count = numArgs.get();
        List<String> arguments;
        if (count == 0) {
            arguments = Collections.emptyList();
        } else {
            arguments = new ArrayList<>(count);
            Pointer[] argNames = argNameRef.getValue().getPointerArray(0, count);
            Pointer[] argTypes = argTypeRef.getValue().getPointerArray(0, count);
            for (int i = 0; i < argTypes.length; ++i) {
                String type = argTypes[i].getString(0);
                if (!type.startsWith("NDArray") && !type.startsWith("Symbol")) {
                    arguments.add(argNames[i].getString(0));
                }
            }
        }

        return new FunctionInfo(handle, functionName, arguments, null);
    }

    /*
    int MXFuncGetInfo(Pointer fun, String name[], String description[], IntBuffer num_args,
                      PointerByReference arg_names, PointerByReference arg_type_infos,
                      PointerByReference arg_descriptions, String return_type[]);

    int MXFuncDescribe(Pointer fun, IntBuffer num_use_vars, IntBuffer num_scalars,
                       IntBuffer num_mutate_vars, IntBuffer type_mask);

    int MXFuncInvoke(Pointer fun, PointerByReference use_vars, FloatBuffer scalar_args,
                     PointerByReference mutate_vars);

    int MXFuncInvokeEx(Pointer fun, PointerByReference use_vars, FloatBuffer scalar_args,
                       PointerByReference mutate_vars, int num_params,
                       PointerByReference param_keys, PointerByReference param_vals);
    */

    /////////////////////////////////
    // System information
    /////////////////////////////////

    public static int getGpuCount() {
        IntBuffer count = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXGetGPUCount(count));

        return count.get();
    }

    public static long[] getGpuMemory(Context context) {
        int deviceId = context.getDeviceId();
        long[] ret = new long[2];

        LongBuffer freeMem = LongBuffer.wrap(ret, 0, 1);
        LongBuffer totalMem = LongBuffer.wrap(ret, 1, 1);

        checkCall(MxnetLibrary.INSTANCE.MXGetGPUMemoryInformation64(deviceId, freeMem, totalMem));

        return ret;
    }

    public static void setOmpThreads(int threads) {
        checkCall(MxnetLibrary.INSTANCE.MXSetNumOMPThreads(threads));
    }

    public static int setBulkSize(int bulkSize) {
        IntBuffer prevBulkSize = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXEngineSetBulkSize(bulkSize, prevBulkSize));

        return prevBulkSize.get();
    }

    /////////////////////////////////
    // Utilities
    /////////////////////////////////

    public static int randomSeed(int seed) {
        return MxnetLibrary.INSTANCE.MXRandomSeed(seed);
    }

    public static int randomSeed(int seed, Context context) {
        Context.DeviceType type = context.getDeviceType();
        return MxnetLibrary.INSTANCE.MXRandomSeedContext(
                seed, type.getType(), context.getDeviceId());
    }

    public static void notifyShutdown() {
        checkCall(MxnetLibrary.INSTANCE.MXNotifyShutdown());
    }

    /////////////////////////////////
    // Profiler information
    /////////////////////////////////

    /*
    public static int setProcessProfilerConfig(int numParams, String keys[], String vals[],
                                               Pointer kvstoreHandle) {

    }

    int MXSetProfilerConfig(int num_params, String keys[], String vals[]);

    int MXSetProcessProfilerState(int state, int profile_process, Pointer kvStoreHandle);

    int MXSetProfilerState(int state);

    int MXDumpProcessProfile(int finished, int profile_process, Pointer kvStoreHandle);

    int MXDumpProfile(int finished);

    int MXAggregateProfileStatsPrint(String out_str[], int reset);

    int MXProcessProfilePause(int paused, int profile_process, Pointer kvStoreHandle);

    int MXProfilePause(int paused);

    int MXProfileCreateDomain(String domain, PointerByReference out);

    int MXProfileCreateTask(Pointer domain, Pointer task_name, PointerByReference out);

    int MXProfileCreateTask(Pointer domain, String task_name, PointerByReference out);

    int MXProfileCreateFrame(Pointer domain, String frame_name, PointerByReference out);

    int MXProfileCreateEvent(String event_name, PointerByReference out);

    int MXProfileCreateCounter(Pointer domain, String counter_name, PointerByReference out);

    int MXProfileDestroyHandle(Pointer frame_handle);

    int MXProfileDurationStart(Pointer duration_handle);

    int MXProfileDurationStop(Pointer duration_handle);

    int MXProfileSetCounter(Pointer counter_handle, long value);

    int MXProfileAdjustCounter(Pointer counter_handle, long value);

    int MXProfileSetMarker(Pointer domain, String instant_marker_name, String scope);
    */

    /////////////////////////////////
    // NDArray
    /////////////////////////////////

    public static Pointer createNdArray() {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayCreateNone(ref));

        return ref.getValue();
    }

    public static Pointer createNdArray(
            Context context, Shape shape, DataType dtype, int size, boolean delayedAlloc) {
        int deviceType = context.getDeviceType().getType();
        int deviceId = context.getDeviceId();
        int delay = delayedAlloc ? 1 : 0;

        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXNDArrayCreateEx(
                        shape.getShape(), size, deviceType, deviceId, delay, dtype.ordinal(), ref));

        return ref.getValue();
    }

    public static Pointer loadFromBytes(byte[] buf, int offset, int size) {
        Memory memory = new Memory(size);
        memory.write(0, buf, offset, size);

        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXNDArrayLoadFromRawBytes(memory, new NativeSize(size), ref));

        return ref.getValue();
    }

    public static void saveNdArray(String file, Pointer[] ndArrays, String[] keys) {
        PointerArray array = new PointerArray(ndArrays);
        checkCall(MxnetLibrary.INSTANCE.MXNDArraySave(file, ndArrays.length, array, keys));
    }

    public static Pointer[] loadNdArray(String file) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        IntBuffer namesSize = IntBuffer.allocate(1);
        PointerByReference namesRef = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayLoad(file, size, ref, namesSize, namesRef));

        return ref.getValue().getPointerArray(0, size.get());
    }

    public static ByteBuffer readBytes(Pointer ndArray) {
        NativeSizeByReference size = new NativeSizeByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArraySaveRawBytes(ndArray, size, ref));

        return ref.getValue().getByteBuffer(0, size.getValue().longValue());
    }

    public static void freeNdArray(Pointer ndArray) {
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayFree(ndArray));
    }

    public static void waitToRead(Pointer ndArray) {
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayWaitToRead(ndArray));
    }

    public static void waitToWrite(Pointer ndArray) {
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayWaitToWrite(ndArray));
    }

    public static void waitAll() {
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayWaitAll());
    }

    public static void syncCopyToCPU(Pointer ndArray, Pointer data, int len) {
        NativeSize size = new NativeSize(len);
        checkCall(MxnetLibrary.INSTANCE.MXNDArraySyncCopyToCPU(ndArray, data, size));
    }

    public static void syncCopyFromCPU(Pointer ndArray, Buffer data) {
        NativeSize size = new NativeSize(data.remaining());
        Pointer pointer = Native.getDirectBufferPointer(data);
        checkCall(MxnetLibrary.INSTANCE.MXNDArraySyncCopyFromCPU(ndArray, pointer, size));
    }

    public static void imperativeInvoke(
            Pointer function,
            Pointer src,
            PointerByReference destRef,
            PairList<String, String> params) {
        PointerByReference inputs = new PointerByReference(src);
        String[] keys;
        String[] values;
        if (params == null) {
            keys = new String[0];
            values = new String[0];
        } else {
            keys = params.keys();
            values = params.values();
        }
        IntBuffer size = IntBuffer.allocate(1);
        size.put(0, 1);

        checkCall(
                MxnetLibrary.INSTANCE.MXImperativeInvoke(
                        function, 1, inputs, size, destRef, keys.length, keys, values));
    }

    public static StorageType getStorageType(Pointer ndArray) {
        IntBuffer type = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetStorageType(ndArray, type));
        return StorageType.fromValue(type.get());
    }

    public static Context getContext(Pointer ndArray) {
        IntBuffer deviceType = IntBuffer.allocate(1);
        IntBuffer deviceId = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetContext(ndArray, deviceType, deviceId));
        return new Context(Context.DeviceType.fromValue(deviceType.get(0)), deviceId.get(0));
    }

    public static Shape getShape(Pointer ndArray) {
        IntBuffer dim = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetShape(ndArray, dim, ref));
        int[] shape = ref.getValue().getIntArray(0, dim.get());
        return new Shape(shape);
    }

    public static DataType getDataType(Pointer ndArray) {
        IntBuffer dataType = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetDType(ndArray, dataType));
        return DataType.values()[dataType.get()];
    }

    public static DataType getAuxType(Pointer ndArray, int index) {
        IntBuffer dataType = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetAuxType(ndArray, index, dataType));
        return DataType.values()[dataType.get()];
    }

    public static Pointer getAuxNdArray(Pointer ndArray, int index) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetAuxNDArray(ndArray, index, ref));
        return ref.getValue();
    }

    public static Pointer getDataNdArray(Pointer ndArray) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetDataNDArray(ndArray, ref));
        return ref.getValue();
    }

    public static Pointer getGrad(Pointer ndArray) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayGetGrad(ndArray, ref));
        return ref.getValue();
    }

    public static Pointer ndArrayAt(Pointer ndArray, int index) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArrayAt(ndArray, index, ref));
        return ref.getValue();
    }

    public static Pointer slice(Pointer ndArray, int begin, int end) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXNDArraySlice(ndArray, begin, end, ref));
        return ref.getValue();
    }

    /*
    int MXImperativeInvokeEx(Pointer creator, int num_inputs, PointerByReference inputs,
                             IntBuffer num_outputs, PointerByReference outputs, int num_params,
                             String param_keys[], String param_vals[],
                             PointerByReference out_stypes);

    int MXNDArraySyncCopyFromCPU(Pointer handle, Pointer data, NativeSize size);

    int MXNDArraySyncCopyFromNDArray(Pointer handle_dst, Pointer handle_src, int i);

    int MXNDArraySyncCheckFormat(Pointer handle, byte full_check);


    int MXNDArrayReshape(Pointer handle, int ndim, IntBuffer dims, PointerByReference out);

    int MXNDArrayReshape64(Pointer handle, int ndim, LongBuffer dims, byte reverse,
                           PointerByReference out);

    int MXNDArrayGetData(Pointer handle, PointerByReference out_pdata);

    int MXNDArrayToDLPack(Pointer handle, PointerByReference out_dlpack);

    int MXNDArrayFromDLPack(Pointer dlpack, PointerByReference out_handle);

    int MXNDArrayCallDLPackDeleter(Pointer dlpack);

    int MXNDArrayGetDType(Pointer handle, IntBuffer out_dtype);

    int MXNDArrayGetAuxType(Pointer handle, int i, IntBuffer out_type);

    int MXNDArrayGetAuxNDArray(Pointer handle, int i, PointerByReference out);

    int MXNDArrayGetDataNDArray(Pointer handle, PointerByReference out);

    int MXNDArrayGetContext(Pointer handle, IntBuffer out_dev_type, IntBuffer out_dev_id);

    int MXNDArrayGetGrad(Pointer handle, PointerByReference out);

    int MXNDArrayDetach(Pointer handle, PointerByReference out);

    int MXNDArraySetGradState(Pointer handle, int state);

    int MXNDArrayGetGradState(Pointer handle, IntBuffer out);

    int MXListFunctions(IntBuffer out_size, PointerByReference out_array);


    int MXAutogradSetIsRecording(int is_recording, IntBuffer prev);

    int MXAutogradSetIsTraining(int is_training, IntBuffer prev);

    int MXAutogradIsRecording(ByteBuffer curr);

    int MXAutogradIsTraining(ByteBuffer curr);

    int MXAutogradMarkVariables(int num_var, PointerByReference var_handles, IntBuffer reqs_array,
                                PointerByReference grad_handles);

    int MXAutogradComputeGradient(int num_output, PointerByReference output_handles);

    int MXAutogradBackward(int num_output, PointerByReference output_handles,
                           PointerByReference ograd_handles, int retain_graph);

    int MXAutogradBackwardEx(int num_output, PointerByReference output_handles,
                             PointerByReference ograd_handles, int num_variables,
                             PointerByReference var_handles, int retain_graph, int create_graph,
                             int is_train, PointerByReference grad_handles,
                             PointerByReference grad_stypes);


    int MXAutogradGetSymbol(Pointer handle, PointerByReference out);


    int MXCreateCachedOp(Pointer handle, PointerByReference out);


    int MXCreateCachedOpEx(Pointer handle, int num_flags, String keys[], String vals[],
                           PointerByReference out);


    int MXFreeCachedOp(Pointer handle);


    int MXInvokeCachedOp(Pointer handle, int num_inputs, PointerByReference inputs,
                         IntBuffer num_outputs, PointerByReference outputs);

    int MXInvokeCachedOpEx(Pointer handle, int num_inputs, PointerByReference inputs,
                           IntBuffer num_outputs, PointerByReference outputs,
                           PointerByReference out_stypes);


    int MXListAllOpNames(IntBuffer out_size, PointerByReference out_array);
    */

    /////////////////////////////////
    // MXNet Symbols
    /////////////////////////////////

    public static Pointer getSymbolOutput(Pointer symbol, int index) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetOutput(symbol, index, ref));
        return ref.getValue();
    }

    public static String[] listSymbolOutputs(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolListOutputs(symbol, size, ref));

        return toStringArray(ref, size.get());
    }

    public static String symbolToJson(Pointer symbol) {
        String[] out = new String[1];
        checkCall(MxnetLibrary.INSTANCE.MXSymbolSaveToJSON(symbol, out));
        return out[0];
    }

    public static void freeSymbol(Pointer symbol) {
        checkCall(MxnetLibrary.INSTANCE.MXSymbolFree(symbol));
    }

    public static void saveSymbol(Pointer symbol, String path) {
        checkCall(MxnetLibrary.INSTANCE.MXSymbolSaveToFile(symbol, path));
    }

    public static Pointer copySymbol(Pointer symbol) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolCopy(symbol, ref));
        return ref.getValue();
    }

    public static String getSymbolDebugString(Pointer symbol) {
        String[] out = new String[1];
        checkCall(MxnetLibrary.INSTANCE.MXSymbolPrint(symbol, out));
        return out[0];
    }

    public static String getSymbolName(Pointer symbol) {
        String[] out = new String[1];
        IntBuffer success = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetName(symbol, out, success));
        if (success.get() == 1) {
            return out[0];
        }
        return null;
    }

    public static String getSymbolAttr(Pointer symbol, String key) {
        String[] out = new String[1];
        IntBuffer success = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetAttr(symbol, key, out, success));
        if (success.get() == 1) {
            return out[0];
        }
        return null;
    }

    public static void setSymbolAttr(Pointer symbol, String key, String value) {
        checkCall(MxnetLibrary.INSTANCE.MXSymbolSetAttr(symbol, key, value));
    }

    public static PairList<String, String> listSymbolAttr(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolListAttr(symbol, size, ref));

        return toPairList(ref, size.get());
    }

    public static PairList<String, String> listSymbolAttrShallow(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolListAttrShallow(symbol, size, ref));

        return toPairList(ref, size.get());
    }

    public static String[] listSymbolArguments(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolListArguments(symbol, size, ref));

        return toStringArray(ref, size.get());
    }

    public static int getSymbolNumOutputs(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetNumOutputs(symbol, size));
        return size.get();
    }

    public static Pointer getSymbolInternals(Pointer symbol) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetInternals(symbol, ref));
        return ref.getValue();
    }

    public static String getSymbolChildren(Pointer symbol) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetChildren(symbol, ref));
        return ref.getValue().getString(0);
    }

    public static String[] listSymbolAuxiliaryStates(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolListAuxiliaryStates(symbol, size, ref));

        return toStringArray(ref, size.get());
    }

    public static Pointer[] listAtomicSymbolCreators() {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolListAtomicSymbolCreators(outSize, ref));

        int size = outSize.get();
        return ref.getValue().getPointerArray(0, size);
    }

    public static String getAtomicSymbolName(Pointer symbol) {
        String[] ret = new String[1];
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetAtomicSymbolName(symbol, ret));
        return ret[0];
    }

    public static String getInputSymbols(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolGetInputSymbols(symbol, ref, size));
        return ref.getValue().getString(0);
    }

    public static String cutSubgraph(Pointer symbol) {
        IntBuffer inputSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolCutSubgraph(symbol, ref, inputSize));
        return ref.getValue().getString(0);
    }

    public static void getAtomicSymbolInfo(Pointer symbol, String name) {
        String[] nameRef = new String[] {name};
        String[] description = new String[1];
        IntBuffer numArgs = IntBuffer.allocate(1);
        PointerByReference argNames = new PointerByReference();
        PointerByReference argTypes = new PointerByReference();
        PointerByReference argDescs = new PointerByReference();
        String[] keyVarNumArgs = new String[1];
        String[] returnType = new String[1];
        checkCall(
                MxnetLibrary.INSTANCE.MXSymbolGetAtomicSymbolInfo(
                        symbol,
                        nameRef,
                        description,
                        numArgs,
                        argNames,
                        argTypes,
                        argDescs,
                        keyVarNumArgs,
                        returnType));
    }

    public static Pointer createAtomicSymbol(Pointer symbol, String[] keys, String[] values) {
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXSymbolCreateAtomicSymbol(
                        symbol, keys.length, keys, values, ref));
        return ref.getValue();
    }

    public static Pointer createVariable(String name) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolCreateVariable(name, ref));
        return ref.getValue();
    }

    public static Pointer createGroup(int numOfSymbols, Pointer symbols) {
        PointerByReference symbolsRef = new PointerByReference(symbols);
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolCreateGroup(numOfSymbols, symbolsRef, ref));
        return ref.getValue();
    }

    public static Pointer createSymbolFromFile(String path) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolCreateFromFile(path, ref));
        return ref.getValue();
    }

    public static Pointer createSymbolFromJson(String json) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXSymbolCreateFromJSON(json, ref));
        return ref.getValue();
    }

    public static Pointer compose(Pointer symbol, String name, String[] keys) {
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolCompose(symbol, name, keys.length, keys, ref));
        return ref.getValue();
    }

    public static Pointer grad(Pointer symbol, String name, int numWrt, String[] wrt) {
        PointerByReference ref = new PointerByReference();

        checkCall(MxnetLibrary.INSTANCE.MXSymbolCompose(symbol, name, numWrt, wrt, ref));
        return ref.getValue();
    }

    public static Pointer inferShape(Pointer symbol, String[] keys) {
        IntBuffer argIndex = IntBuffer.allocate(1);
        IntBuffer argShapeData = IntBuffer.allocate(1);
        IntBuffer inShapeSize = IntBuffer.allocate(1);
        PointerByReference inShapeNDim = new PointerByReference();
        PointerByReference inShapeData = new PointerByReference();
        IntBuffer outShapeSize = IntBuffer.allocate(1);
        PointerByReference outShapeNDim = new PointerByReference();
        PointerByReference outShapeData = new PointerByReference();
        IntBuffer auxShapeSize = IntBuffer.allocate(1);
        PointerByReference auxShapeNDim = new PointerByReference();
        PointerByReference auxShapeData = new PointerByReference();
        IntBuffer complete = IntBuffer.allocate(1);

        checkCall(
                MxnetLibrary.INSTANCE.MXSymbolInferShape(
                        symbol,
                        keys.length,
                        keys,
                        argIndex.array(),
                        argShapeData.array(),
                        inShapeSize,
                        inShapeNDim,
                        inShapeData,
                        outShapeSize,
                        outShapeNDim,
                        outShapeData,
                        auxShapeSize,
                        auxShapeNDim,
                        auxShapeData,
                        complete));
        if (complete.get() == 1) {
            return outShapeData.getValue();
        }
        return null;
    }

    public static Pointer inferShapePartial(Pointer symbol, int numArgs, String[] keys) {
        IntBuffer argIndex = IntBuffer.allocate(1);
        IntBuffer argShapeData = IntBuffer.allocate(1);
        IntBuffer inShapeSize = IntBuffer.allocate(1);
        PointerByReference inShapeNDim = new PointerByReference();
        PointerByReference inShapeData = new PointerByReference();
        IntBuffer outShapeSize = IntBuffer.allocate(1);
        PointerByReference outShapeNDim = new PointerByReference();
        PointerByReference outShapeData = new PointerByReference();
        IntBuffer auxShapeSize = IntBuffer.allocate(1);
        PointerByReference auxShapeNDim = new PointerByReference();
        PointerByReference auxShapeData = new PointerByReference();
        IntBuffer complete = IntBuffer.allocate(1);

        checkCall(
                MxnetLibrary.INSTANCE.MXSymbolInferShapePartial(
                        symbol,
                        numArgs,
                        keys,
                        argIndex.array(),
                        argShapeData.array(),
                        inShapeSize,
                        inShapeNDim,
                        inShapeData,
                        outShapeSize,
                        outShapeNDim,
                        outShapeData,
                        auxShapeSize,
                        auxShapeNDim,
                        auxShapeData,
                        complete));
        if (complete.get() == 1) {
            return outShapeData.getValue();
        }
        return null;
    }

    public static Pointer inferType(Pointer symbol, String[] keys) {
        int[] argTypeData = new int[1];
        IntBuffer inTypeSize = IntBuffer.allocate(1);
        PointerByReference inTypeData = new PointerByReference();
        IntBuffer outTypeSize = IntBuffer.allocate(1);
        PointerByReference outTypeData = new PointerByReference();
        IntBuffer auxTypeSize = IntBuffer.allocate(1);
        PointerByReference auxTypeData = new PointerByReference();
        IntBuffer complete = IntBuffer.allocate(1);

        checkCall(
                MxnetLibrary.INSTANCE.MXSymbolInferType(
                        symbol,
                        keys.length,
                        keys,
                        argTypeData,
                        inTypeSize,
                        inTypeData,
                        outTypeSize,
                        outTypeData,
                        auxTypeSize,
                        auxTypeData,
                        complete));
        if (complete.get() == 1) {
            return outTypeData.getValue();
        }
        return null;
    }

    public static Pointer quantizeSymbol(
            Pointer symbol,
            String[] excludedSymbols,
            String[] offlineParams,
            String quantizedDType,
            byte calibQuantize) {
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXQuantizeSymbol(
                        symbol,
                        ref,
                        excludedSymbols.length,
                        excludedSymbols,
                        offlineParams.length,
                        offlineParams,
                        quantizedDType,
                        calibQuantize));
        return ref.getValue();
    }

    public static Pointer setCalibTableToQuantizedSymbol(
            Pointer symbol,
            String[] layerNames,
            FloatBuffer lowQuantiles,
            FloatBuffer highQuantiles) {
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXSetCalibTableToQuantizedSymbol(
                        symbol, layerNames.length, layerNames, lowQuantiles, highQuantiles, ref));
        return ref.getValue();
    }

    public static Pointer genBackendSubgraph(Pointer symbol, String backend) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXGenBackendSubgraph(symbol, backend, ref));
        return ref.getValue();
    }

    /////////////////////////////////
    // MXNet Executors
    /////////////////////////////////

    public static void freeExecutor(Pointer executor) {
        checkCall(MxnetLibrary.INSTANCE.MXExecutorFree(executor));
    }

    public static String getExecutorDebugString(Pointer executor) {
        String[] ret = new String[1];
        checkCall(MxnetLibrary.INSTANCE.MXExecutorPrint(executor, ret));
        return ret[0];
    }

    public static void forward(Pointer executor, boolean isTrain) {
        checkCall(MxnetLibrary.INSTANCE.MXExecutorForward(executor, isTrain ? 1 : 0));
    }

    public static Pointer backward(Pointer executor, int length) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXExecutorBackward(executor, length, ref));
        return ref.getValue();
    }

    public static Pointer backwardEx(Pointer executor, int length, boolean isTrain) {
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXExecutorBackwardEx(executor, length, ref, isTrain ? 1 : 0));
        return ref.getValue();
    }

    public static NdArray[] getExecutorOutputs(ResourceAllocator alloc, Pointer executor) {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXExecutorOutputs(executor, outSize, ref));
        int size = outSize.get();
        Pointer[] pointers = ref.getValue().getPointerArray(0, size);
        NdArray[] ndArrays = new NdArray[size];
        for (int i = 0; i < size; ++i) {
            ndArrays[i] = new NdArray(alloc, pointers[i]);
        }
        return ndArrays;
    }

    public static Pointer bindExecutor(
            Pointer executor, Context context, int len, int auxStatesLen) {
        int deviceId = context.getDeviceId();
        int deviceType = context.getDeviceType().getType();
        PointerByReference inArgs = new PointerByReference();
        PointerByReference argGradStore = new PointerByReference();
        IntBuffer gradReqType = IntBuffer.allocate(1);
        PointerByReference auxStates = new PointerByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXExecutorBind(
                        executor,
                        deviceType,
                        deviceId,
                        len,
                        inArgs,
                        argGradStore,
                        gradReqType,
                        auxStatesLen,
                        auxStates,
                        ref));
        return ref.getValue();
    }

    public static Pointer bindExecutorX(
            Pointer executor,
            Context context,
            int len,
            int auxStatesLen,
            String[] keys,
            int[] deviceTypes,
            int[] deviceIds) {
        int deviceId = context.getDeviceId();
        int deviceType = context.getDeviceType().getType();
        PointerByReference inArgs = new PointerByReference();
        PointerByReference argGradStore = new PointerByReference();
        IntBuffer gradReqType = IntBuffer.allocate(1);
        PointerByReference auxStates = new PointerByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXExecutorBindX(
                        executor,
                        deviceType,
                        deviceId,
                        keys.length,
                        keys,
                        deviceTypes,
                        deviceIds,
                        len,
                        inArgs,
                        argGradStore,
                        gradReqType,
                        auxStatesLen,
                        auxStates,
                        ref));
        return ref.getValue();
    }

    public static Pointer bindExecutorEX(
            Pointer executor,
            Context context,
            int len,
            int auxStatesLen,
            String[] keys,
            int[] deviceTypes,
            int[] deviceIds,
            Pointer sharedExecutor) {
        int deviceId = context.getDeviceId();
        int deviceType = context.getDeviceType().getType();
        PointerByReference inArgs = new PointerByReference();
        PointerByReference argGradStore = new PointerByReference();
        IntBuffer gradReqType = IntBuffer.allocate(1);
        PointerByReference auxStates = new PointerByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXExecutorBindEX(
                        executor,
                        deviceType,
                        deviceId,
                        keys.length,
                        keys,
                        deviceTypes,
                        deviceIds,
                        len,
                        inArgs,
                        argGradStore,
                        gradReqType,
                        auxStatesLen,
                        auxStates,
                        sharedExecutor,
                        ref));
        return ref.getValue();
    }

    public static Pointer reshapeExecutor(
            boolean partialShaping,
            boolean allowUpSizing,
            Context context,
            String[] keys,
            int[] deviceTypes,
            int[] deviceIds,
            String[] providedArgShapeNames,
            IntBuffer providedArgShapeData,
            IntBuffer providedArgShapeIdx,
            IntBuffer numInArgs,
            PointerByReference inArgs,
            PointerByReference argGrads,
            IntBuffer numAuxStates,
            PointerByReference auxStates,
            Pointer sharedExecutor) {
        int deviceId = context.getDeviceId();
        int deviceType = context.getDeviceType().getType();
        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXExecutorReshape(
                        partialShaping ? 1 : 0,
                        allowUpSizing ? 1 : 0,
                        deviceType,
                        deviceId,
                        keys.length,
                        keys,
                        deviceTypes,
                        deviceIds,
                        providedArgShapeNames.length,
                        providedArgShapeNames,
                        providedArgShapeData.array(),
                        providedArgShapeIdx.array(),
                        numInArgs,
                        inArgs,
                        argGrads,
                        numAuxStates,
                        auxStates,
                        sharedExecutor,
                        ref));
        return ref.getValue();
    }

    public static Pointer getOptimizedSymbol(Pointer executor) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXExecutorGetOptimizedSymbol(executor, ref));
        return ref.getValue();
    }

    public static void setMonitorCallback(
            Pointer executor,
            MxnetLibrary.ExecutorMonitorCallback callback,
            Pointer callbackHandle) {
        checkCall(
                MxnetLibrary.INSTANCE.MXExecutorSetMonitorCallback(
                        executor, callback, callbackHandle));
    }

    /////////////////////////////////
    // MXNet Executors
    /////////////////////////////////

    public static Pointer[] listDataIters() {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXListDataIters(outSize, ref));
        return ref.getValue().getPointerArray(0, outSize.get());
    }

    public static Pointer createIter(Pointer iter, String[] keys, String[] values) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXDataIterCreateIter(iter, keys.length, keys, values, ref));
        return ref.getValue();
    }

    public static String getIterInfo(Pointer iter) {
        String[] name = new String[1];
        String[] description = new String[1];
        IntBuffer numArgs = IntBuffer.allocate(1);
        PointerByReference argNames = new PointerByReference();
        PointerByReference argTypes = new PointerByReference();
        PointerByReference argDesc = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXDataIterGetIterInfo(
                        iter, name, description, numArgs, argNames, argTypes, argDesc));
        return name[0];
    }

    public static void freeDataIter(Pointer iter) {
        checkCall(MxnetLibrary.INSTANCE.MXDataIterFree(iter));
    }

    public static int next(Pointer iter) {
        IntBuffer ret = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXDataIterNext(iter, ret));
        return ret.get();
    }

    public static void beforeFirst(Pointer iter) {
        checkCall(MxnetLibrary.INSTANCE.MXDataIterBeforeFirst(iter));
    }

    public static Pointer getData(Pointer iter) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXDataIterGetData(iter, ref));
        return ref.getValue();
    }

    public static Pointer getIndex(Pointer iter) {
        LongBuffer outSize = LongBuffer.wrap(new long[1]);
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXDataIterGetIndex(iter, ref, outSize));
        return ref.getValue();
    }

    public static int getPadNum(Pointer iter) {
        IntBuffer outSize = IntBuffer.allocate(1);
        checkCall(MxnetLibrary.INSTANCE.MXDataIterGetPadNum(iter, outSize));
        return outSize.get();
    }

    public static String getDataIterLabel(Pointer iter) {
        PointerByReference ref = new PointerByReference();
        checkCall(MxnetLibrary.INSTANCE.MXDataIterGetLabel(iter, ref));
        return ref.getValue().getString(0);
    }

    /*
    int MXInitPSEnv(int num_vars, String keys[], String vals[]);


    int MXKVStoreCreate(String type, PointerByReference out);


    int MXKVStoreSetGradientCompression(Pointer handle, int num_params, String keys[],
                                        String vals[]);


    int MXKVStoreFree(Pointer handle);


    int MXKVStoreInit(Pointer handle, int num, int keys[], PointerByReference vals);


    int MXKVStoreInitEx(Pointer handle, int num, String keys[], PointerByReference vals);

    int MXKVStorePush(Pointer handle, int num, int keys[], PointerByReference vals, int priority);

    int MXKVStorePushEx(Pointer handle, int num, String keys[], PointerByReference vals,
                        int priority);

    int MXKVStorePullWithSparse(Pointer handle, int num, int keys[], PointerByReference vals,
                                int priority, byte ignore_sparse);

    int MXKVStorePullWithSparseEx(Pointer handle, int num, String keys[], PointerByReference vals,
                                  int priority, byte ignore_sparse);


    int MXKVStorePull(Pointer handle, int num, int keys[], PointerByReference vals, int priority);

    int MXKVStorePullEx(Pointer handle, int num, String keys[], PointerByReference vals,
                        int priority);

    int MXKVStorePullRowSparse(Pointer handle, int num, int keys[], PointerByReference vals,
                               PointerByReference row_ids, int priority);

    int MXKVStorePullRowSparseEx(Pointer handle, int num, String keys[], PointerByReference vals,
                                 PointerByReference row_ids, int priority);


    int MXKVStoreSetUpdater(Pointer handle, MxnetLibrary.MXKVStoreUpdater updater,
                            Pointer updater_handle);


    int MXKVStoreSetUpdaterEx(Pointer handle, MxnetLibrary.MXKVStoreUpdater updater,
                              MxnetLibrary.MXKVStoreStrUpdater str_updater, Pointer updater_handle);

    int MXKVStoreGetType(Pointer handle, String type[]);


    int MXKVStoreGetRank(Pointer handle, IntBuffer ret);

    int MXKVStoreGetGroupSize(Pointer handle, IntBuffer ret);

    int MXKVStoreIsWorkerNode(IntBuffer ret);

    int MXKVStoreIsServerNode(IntBuffer ret);

    int MXKVStoreIsSchedulerNode(IntBuffer ret);


    int MXKVStoreBarrier(Pointer handle);


    int MXKVStoreSetBarrierBeforeExit(Pointer handle, int barrier_before_exit);


    int MXKVStoreRunServer(Pointer handle, MxnetLibrary.MXKVStoreServerController controller,
                           Pointer controller_handle);


    int MXKVStoreSendCommmandToServers(Pointer handle, int cmd_id, String cmd_body);

    int MXKVStoreGetNumDeadNode(Pointer handle, int node_id, IntBuffer number, int timeout_sec);


    int MXRecordIOWriterCreate(String uri, PointerByReference out);


    int MXRecordIOWriterFree(Pointer handle);


    int MXRecordIOWriterWriteRecord(Pointer handle, String buf, NativeSize size);


    int MXRecordIOWriterTell(Pointer handle, NativeSizeByReference pos);


    int MXRecordIOReaderCreate(String uri, PointerByReference out);


    int MXRecordIOReaderFree(Pointer handle);


    int MXRecordIOReaderReadRecord(Pointer handle, String buf[], NativeSizeByReference size);


    int MXRecordIOReaderSeek(Pointer handle, NativeSize pos);


    int MXRecordIOReaderTell(Pointer handle, NativeSizeByReference pos);


    int MXRtcCreate(ByteBuffer name, int num_input, int num_output, PointerByReference input_names,
                    PointerByReference output_names, PointerByReference inputs,
                    PointerByReference outputs, ByteBuffer kernel, PointerByReference out);


    int MXRtcPush(Pointer handle, int num_input, int num_output, PointerByReference inputs,
                  PointerByReference outputs, int gridDimX, int gridDimY, int gridDimZ,
                  int blockDimX, int blockDimY, int blockDimZ);


    int MXRtcFree(Pointer handle);


    int MXCustomOpRegister(String op_type, MxnetLibrary.CustomOpPropCreator creator);


    int MXCustomFunctionRecord(int num_inputs, PointerByReference inputs, int num_outputs,
                               PointerByReference outputs, MXCallbackList callbacks);


    int MXRtcCudaModuleCreate(String source, int num_options, String options[], int num_exports,
                              String exports[], PointerByReference out);


    int MXRtcCudaModuleFree(Pointer handle);


    int MXRtcCudaKernelCreate(Pointer handle, String name, int num_args, IntBuffer is_ndarray,
                              IntBuffer is_const, IntBuffer arg_types, PointerByReference out);


    int MXRtcCudaKernelFree(Pointer handle);


    int MXRtcCudaKernelCall(Pointer handle, int dev_id, PointerByReference args, int grid_dim_x,
                            int grid_dim_y, int grid_dim_z, int block_dim_x, int block_dim_y,
                            int block_dim_z, int shared_mem);


    int MXNDArrayGetSharedMemHandle(Pointer handle, IntBuffer shared_pid, IntBuffer shared_id);


    int MXNDArrayCreateFromSharedMem(int shared_pid, int shared_id, IntBuffer shape, int ndim,
                                     int dtype, PointerByReference out);
    */

    //////////////////////////////////
    // c_predict_api
    //////////////////////////////////

    public static Pointer createPredictor(
            String symbol, ByteBuffer parameter, Context context, DataDesc dataDesc) {
        String[] inputNames = new String[] {dataDesc.getName()};
        Shape shape = dataDesc.getShape();

        IntBuffer inputShapeIdx = IntBuffer.wrap(new int[] {0, 4});
        IntBuffer inputShapeData = IntBuffer.wrap(shape.getShape());

        PointerByReference ref = new PointerByReference();
        checkCall(
                MxnetLibrary.INSTANCE.MXPredCreate(
                        symbol,
                        Native.getDirectBufferPointer(parameter),
                        parameter.remaining(),
                        context.getDeviceType().getType(),
                        context.getDeviceId(),
                        1,
                        inputNames,
                        inputShapeIdx.array(),
                        inputShapeData.array(),
                        ref));

        return ref.getValue();
    }

    public static void setPredictorInput(Pointer predictor, String inputName, FloatBuffer data) {
        checkCall(
                MxnetLibrary.INSTANCE.MXPredSetInput(predictor, inputName, data, data.remaining()));
    }

    public static void predictorForward(Pointer predictor) {
        checkCall(MxnetLibrary.INSTANCE.MXPredForward(predictor));
    }

    public static FloatBuffer getPredictorOutput(Pointer predictor) {
        PointerByReference shapeRef = new PointerByReference();
        IntBuffer size = IntBuffer.allocate(1);

        checkCall(MxnetLibrary.INSTANCE.MXPredGetOutputShape(predictor, 0, shapeRef, size));

        int count = size.get();
        Shape shape = new Shape(shapeRef.getValue().getIntArray(0, count));

        FloatBuffer data = FloatBuffer.allocate(shape.product());
        checkCall(MxnetLibrary.INSTANCE.MXPredGetOutput(predictor, 0, data, data.remaining()));

        return data;
    }

    public static void freePredictor(Pointer predictor) {
        checkCall(MxnetLibrary.INSTANCE.MXPredFree(predictor));
    }

    public static void freeNdArrayList(Pointer ndList) {
        checkCall(MxnetLibrary.INSTANCE.MXNDListFree(ndList));
    }

    public static void checkCall(int ret) {
        if (ret != 0) {
            throw new JnaException("MXNet engined call failed: " + getLastError());
        }
    }

    private static String getLastError() {
        return MxnetLibrary.INSTANCE.MXGetLastError();
    }

    public static List<String> readLines(File file) throws IOException {
        if (!file.exists()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        try (FileInputStream is = new FileInputStream(file)) {
            try (Scanner scanner =
                    new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\n|\\r\\n")) {
                while (scanner.hasNext()) {
                    list.add(scanner.next());
                }
            }
        }
        return list;
    }

    private static String[] toStringArray(PointerByReference ref, int size) {
        if (size == 0) {
            return new String[0];
        }

        Pointer[] pointers = ref.getValue().getPointerArray(0, size);

        String[] arr = new String[size];
        for (int i = 0; i < size; ++i) {
            arr[i] = pointers[i].getString(0);
        }

        return arr;
    }

    private static PairList<String, String> toPairList(PointerByReference ref, int size) {
        Pointer[] pointers = ref.getValue().getPointerArray(0, size);

        String[] names = new String[size];
        String[] values = new String[size];
        for (int i = 0; i < size; ++i) {
            String[] pair = pointers[i].getStringArray(0, 2);
            names[i] = pair[0];
            values[i] = pair[1];
        }

        return new PairList<>(names, values);
    }

    private static String getOpNamePrefix(String name) {
        for (String prefix : OP_NAME_PREFIX) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return name;
    }
}
