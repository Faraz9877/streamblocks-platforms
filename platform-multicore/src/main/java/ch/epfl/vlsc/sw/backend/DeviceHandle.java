package ch.epfl.vlsc.sw.backend;

import ch.epfl.vlsc.platformutils.PathUtils;
import ch.epfl.vlsc.sw.ir.PartitionHandle;
import ch.epfl.vlsc.platformutils.Emitter;
import ch.epfl.vlsc.sw.ir.PartitionLink;
import ch.epfl.vlsc.sw.ir.PartitionHandle.Field;
import ch.epfl.vlsc.sw.ir.PartitionHandle.Method;
import ch.epfl.vlsc.sw.ir.PartitionHandle.Type;
import ch.epfl.vlsc.sw.ir.PartitionHandle.Pair;

import org.multij.Module;
import org.multij.Binding;
import org.multij.BindingKind;

import se.lth.cs.tycho.ir.decl.GlobalEntityDecl;

import se.lth.cs.tycho.ir.entity.PortDecl;
import se.lth.cs.tycho.ir.network.Instance;
import se.lth.cs.tycho.reporting.Diagnostic;
import se.lth.cs.tycho.ir.util.ImmutableList;
import se.lth.cs.tycho.reporting.CompilationException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * @author Mahyar Emami (mahayr.emami@epfl.ch)
 * Generates the openCL code neccessary for CPU <-> FPGA communication, used by the PartitionLink instance.
 */
@Module
public interface DeviceHandle {
    @Binding(BindingKind.INJECTED)
    MulticoreBackend backend();

    default void unimpl() {
        StackTraceElement[] stackTrace = new Throwable().getStackTrace();

        throw new CompilationException(new Diagnostic(Diagnostic.Kind.ERROR,"device handle " +
                stackTrace[2].getMethodName() + " method not implemented!"));
    }



    default String methodReturn(PartitionHandle handle, Method method) {
        return evalType(method.getReturnType());
    }

    default String evalType(Type type) {
        return type.isEvaluated() ? type.getType().get() : portType(type.getPort().get());
    }

    default String handleClassName(PartitionHandle handle) {
        return handle.getName() + "_t";
    }
    default String evalArg(Pair<Type, String> arg) {
        String type = evalType(arg._1);
        String name = arg._1.isReference() ? "*" + arg._2 : arg._2;
        return type + "\t" + name;
    }

    default String methodName(PartitionHandle handle, Method method) {

        if (method.isGlobal()) {
            return method.getName();
        } else {
            String origName = method.getName();
            String methodName = "DeviceHandle" +
                    String.valueOf(origName.charAt(0)).toUpperCase() + origName.substring(1);
            return methodName;
        }
    }

    default String methodSignature(PartitionHandle handle, Method method) {
        return String.format("%s %s(%s)",
                methodReturn(handle, method), methodName(handle, method), methodArgs(handle, method));
    }
    default Emitter emitter() { return backend().emitter(); }
    default String defaultClEvent() { return "cl_event"; }
    default String ClMem() { return "cl_mem"; }
    default String defaultIntType() { return "uint32_t"; }
    default String defaultSizeType() { return "size_t"; }
    default String memAlignment() { return "MEM_ALIGNMENT"; }

    default String defaultWriteEvents() {
        return "write_events";
    }

    default String defaultReadEvents() {
        return "read_events";
    }

    default String defaultKernelEvent() {
        return "kernel_event";
    }

    default String methodPrefix() { return "DeviceHandle_"; }

    default void OclErr(String format, Object... values) {
        emitter().emit("OCL_ERR(\"%s\");", String.format(format, values));
    }

    default void OclCheck(String format, Object... values) {
        emitter().emit("OCL_CHECK(");
        {
            emitter().increaseIndentation();
            emitter().emit("%s", String.format(format, values));
            emitter().decreaseIndentation();
        }
        emitter().emit(");");
    }

    default void OclMsg(String format, Object... values) {
        emitter().emit("OCL_MSG(\"%s\");", String.format(format, values));
    }


    default String portType(PortDecl port) {
        return backend().typeseval().type(backend().types().declaredPortType(port));
    }


    default void generateDeviceHandle(Instance instance) {

        String networkId = backend().task().getIdentifier().getLast().toString();

        GlobalEntityDecl entityDecl = backend().globalnames().entityDecl(instance.getEntityName(), true);
        // -- sanity/assertion check
        if (!(entityDecl.getEntity() instanceof PartitionLink))
            throw new CompilationException(
                    new Diagnostic(Diagnostic.Kind.ERROR, "Expected an instance of PartitionLink when" +
                            " generating device handle code for the network " + networkId + " but did not find one." +
                            " Make sure the NetworkPartitionPhase and ExtractSWPartitionPhases are turned on."));

        PartitionLink entity = (PartitionLink) entityDecl.getEntity();
        backend().context().getReporter().report(new Diagnostic(Diagnostic.Kind.INFO,
                "Generating OpenCL device handle."));
        generateDeviceHandleSource(entity);
        generateDeviceHandleHeader(entity);

    }

    /**
     * Generates the source file device-handle.c
     * @param entity the PartitionLink entity for which the source file is generated.
     */
    default void generateDeviceHandleSource(PartitionLink entity) {
        // -- open output file
        Path artPlinkPath = PathUtils.createDirectory(PathUtils.getTargetLib(backend().context()), "art-plink");
        emitter().open(artPlinkPath.resolve("device-handle.c"));

        BufferedReader reader;
        // -- read the common methods from plink/device-handle.c in resources
        try {
            reader = new BufferedReader(
                    new InputStreamReader(getClass().getResourceAsStream("/lib/art-plink/device-handle.c")));
            String line = reader.readLine();
            while (line != null) {
                emitter().emitRawLine(line);
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new CompilationException(
                    new Diagnostic(Diagnostic.Kind.ERROR, "Could not read the resource file " +
                            "lib/art-plink/device-handle.c"));
        }


        // -- methods definitions

        getCreateClBuffers(entity);
        getSetArgs(entity);
        getEnqueueWriteBuffers(entity);
        getEnqueueReadBuffers(entity);

        getReleaseMemObjects(entity);

        getSetAndGetPtrs(entity);

        emitter().emit("// -- set request size");
        entity.getInputPorts().stream().forEachOrdered(p -> getSetRequestSize(entity.getHandle(), p));

        emitter().close();
    }

    default Method getMethod(PartitionHandle handle, String methodName) {
        Method method = handle.findMethod(methodName)
                .orElseThrow(() ->
                        new CompilationException(new Diagnostic(Diagnostic.Kind.ERROR, "Can not find " +
                                methodName + " method in the PartitionHandle class.")));

        return method;
    }
    default void getCreateClBuffers(PartitionLink entity) {
        Method method = getMethod(entity.getHandle(), "createCLBuffers");

        emitter().emit("%s {", methodSignature(entity.getHandle(), method));
        {
            emitter().increaseIndentation();
            emitter().emit("dev->buffer_size = sz;");
            OclMsg("Creating input CL buffers\\n");
            emitter().emitNewLine();
            emitter().emit("// -- input buffers");
            for (PortDecl port: entity.getInputPorts()) {
                String size = String.format("dev->buffer_size * sizeof(%s)", portType(port));
                getCreateCLBuffer(port.getName() + "_cl_buffer", "CL_MEM_READ_ONLY", size);

            }
            emitter().emitNewLine();
            emitter().emit("// -- output buffer");
            for (PortDecl port: entity.getOutputPorts()) {
                String size = String.format("dev->buffer_size * sizeof(%s)", portType(port));
                getCreateCLBuffer(port.getName() + "_cl_buffer", "CL_MEM_WRITE_ONLY", size);
            }
            emitter().emitNewLine();
            emitter().emit("// -- consumed and produces size of streams");
            Stream.concat(entity.getInputPorts().stream(), entity.getOutputPorts().stream()).forEach(
                    p ->
                            getCreateCLBuffer(p.getName() + "_cl_size",
                                    "CL_MEM_WRITE_ONLY", "sizeof(" + defaultIntType() + ")")
            );

            emitter().decreaseIndentation();
        }
        emitter().emit("}");
    }

    default void getCreateCLBuffer(String name, String mode, String size) {
        emitter().emit("dev->%s = clCreateBuffer(dev->world.context, %s, %s, NULL, NULL);", name, mode, size);
    }
    default void getSetArgs(PartitionLink entity) {
        Method method = getMethod(entity.getHandle(), "setArgs");

        emitter().emit("%s {", methodSignature(entity.getHandle(), method));
        {
            emitter().increaseIndentation();
            OclMsg("Setting kernel args\\n");
            ImmutableList<PortDecl> ports =
                    ImmutableList.from(
                            Stream.concat(
                                    entity.getInputPorts().stream(),
                                    entity.getOutputPorts().stream())
                                    .collect(Collectors.toList()));
            int kernelIndex = 0;
            for (PortDecl port: entity.getInputPorts()) {
                emitter().emit("// -- request size for %s", port.getName());
                getSetKernelArg(
                        kernelIndex ++,
                        "sizeof(cl_uint)", port.getName() + "_request_size");
            }
            for (PortDecl port: entity.getOutputPorts()) {
                emitter().emit("// -- device available size for outputs, " +
                        "should be at most as big as buffer_size");
                getSetKernelArg(kernelIndex ++, "sizeof(cl_uint)", "buffer_size");
            }

            for (PortDecl port: ports) {
                emitter().emit("// -- stream size buffer for %s", port.getName());
                getSetKernelArg(kernelIndex++, "sizeof(cl_mem)", port.getName() + "_cl_size");
                emitter().emit("// -- device buffer object for %s", port.getName());
                getSetKernelArg(kernelIndex++, "sizeof(cl_mem)", port.getName() + "_cl_buffer");
            }

            emitter().emit("// -- kernel command arg");
            getSetKernelArg(kernelIndex++, "sizeof(cl_ulong)", "kernel_command");
            emitter().emitNewLine();
            emitter().decreaseIndentation();
        }
        emitter().emit("}");
    }

    default void getSetKernelArg(int index, String size, String name) {
        OclCheck("clSetKernelArg(dev->kernel, %d, %s, &dev->%s)", index, size, name);
    }

    default void getEnqueueWriteBuffers(PartitionLink entity) {
        Method method = getMethod(entity.getHandle(), "enqueueWriteBuffers");

        emitter().emit("%s {", methodSignature(entity.getHandle(), method));
        {
            emitter().increaseIndentation();


            emitter().emit("size_t req_sz = 1;");
            emitter().emitNewLine();
            for (PortDecl port: entity.getInputPorts()) {
                emitter().emit("if (dev->%s_request_size > 0) { // -- make sure there is something to send",
                        port.getName(), entity.getInputPorts().indexOf(port));
                {
                    emitter().increaseIndentation();
                    emitter().emit("// -- DMA write transfer for %s", port.getName());
                    OclMsg("Enqueueing write for %s", port.getName());
                    emitter().emit("OCL_CHECK(");
                    {
                        emitter().increaseIndentation();
                        emitter().emit("clEnqueueWriteBuffer(");
                        {
                            emitter().increaseIndentation();
                            emitter().emit("dev->world.command_queue, // -- the command queue");
                            emitter().emit("dev->%s_cl_buffer, // -- the cl device buffer", port.getName());
                            emitter().emit("CL_TRUE, // -- blocking write operation"); // TODO: should become CL_FALSE
                            emitter().emit("0, // -- buffer offset, not use");
                            emitter().emit("dev->%s_request_size * sizeof(%s), // -- size of data transfer in byte",
                                    port.getName(), entity.getInputPorts().indexOf(port), portType(port));
                            emitter().emit("dev->%s_buffer, // -- pointer to the host memory", port.getName());
                            emitter().emit("0, // -- number of events in wait list, writes do not wait for anything");
                            emitter().emit("NULL, // -- the event wait list, not used for writes");
                            emitter().emit("&dev->write_buffer_event[%d])); // -- the generated event",
                                    entity.getInputPorts().indexOf(port));
                            emitter().emitNewLine();
                            emitter().emit("// -- register call back for the write event");
                            emitter().emit("on_completion(dev->write_buffer_event[%d], " +
                                    "&dev->write_buffer_event_info[%1$d]);", entity.getInputPorts().indexOf(port));
                            emitter().decreaseIndentation();
                        }
                        emitter().decreaseIndentation();
                    }
                    emitter().decreaseIndentation();
                }
                emitter().emit("} else { // -- else do not make the DMA transfer");
                {
                    emitter().increaseIndentation();
                    OclMsg("Info: skipping a write transfer of size 0 for %s.\\n", port.getName());
                    emitter().decreaseIndentation();
                }
                emitter().emit("}");
                emitter().emitNewLine();
            }
            emitter().decreaseIndentation();
        }
        emitter().emit("}");
    }

    default void getEnqueueReadBuffers(PartitionLink entity) {
        Method method = getMethod(entity.getHandle(), "enqueueReadBuffers");
        emitter().emit("%s {", methodSignature(entity.getHandle(), method));
        {
            emitter().increaseIndentation();

            emitter().emitNewLine();
            ImmutableList<PortDecl> ports =
                    ImmutableList.from(
                            Stream.concat(
                                    entity.getInputPorts().stream(),
                                    entity.getOutputPorts().stream())
                                    .collect(Collectors.toList()));
            // -- stream size buffers
            emitter().emit("// -- Enqueue read for i/o size buffers");
            for(PortDecl port: ports) {
                OclMsg("Enqueue read for %s\\n", port.getName());
                emitter().emit("OCL_CHECK(");
                {
                    emitter().increaseIndentation();
                    emitter().emit("clEnqueueReadBuffer(");
                    {
                        emitter().increaseIndentation();
                        emitter().emit("dev->world.command_queue, // -- command queue");
                        emitter().emit("dev->%s_cl_size, // -- device buffer", port.getName());
                        emitter().emit("CL_TRUE, //-- blocking read"); // this should remain blocking
                        emitter().emit("0, // -- offset");
                        emitter().emit("sizeof(%s), // -- size of the read transfer in bytes", defaultIntType());
                        emitter().emit("dev->%s_size, // -- host buffer for the stream size", port.getName());
                        emitter().emit("1, // -- number of events to wait on");
                        emitter().emit("&dev->kernel_event, // -- the list of events to wait on");
                        emitter().emit("&dev->read_size_event[%d])); // -- the generated event",
                                ports.indexOf(port));
                        emitter().emitNewLine();
                        emitter().emit("// -- register event call back ");
                        emitter().emit("on_completion(dev->read_size_event[%d], &dev->read_size_event_info[%1$d]);",
                                ports.indexOf(port));
                        emitter().emitNewLine();
                        emitter().decreaseIndentation();
                    }
                    emitter().decreaseIndentation();
                }

            }
            emitter().emitNewLine();
            // -- output buffers
            emitter().emit("// -- Enqueue read for output buffers");
            for(PortDecl port: entity.getOutputPorts()) {
                OclMsg("Enqueue readf for %s\\n", port.getName());

                emitter().emit("if (dev->%s_size[0] > 0) {// -- only read if something was produced",
                        port.getName());
                {
                    emitter().increaseIndentation();
                    emitter().emit("OCL_CHECK(");
                    {
                        emitter().increaseIndentation();
                        emitter().emit("clEnqueueReadBuffer(");
                        {
                            emitter().increaseIndentation();
                            emitter().emit("dev->world.command_queue, // -- command queue");
                            emitter().emit("dev->%s_cl_buffer, // -- device buffer", port.getName());
                            emitter().emit("CL_TRUE, //-- blocking read"); // TODO: Should become CL_FALSE
                            emitter().emit("0, // -- offset");
                            emitter().emit("dev->%s_size[0] * sizeof(%s), // -- size of the read transfer in bytes",
                                    port.getName(), portType(port));
                            emitter().emit("dev->%s_buffer, // -- host buffer for the stream size", port.getName());
                            emitter().emit("1, // -- number of events to wait on");
                            emitter().emit("&dev->kernel_event, // -- the list of events to wait on");
                            emitter().emit("&dev->read_buffer_event[%d])); // -- the generated event",
                                    entity.getOutputPorts().indexOf(port));
                            emitter().emitNewLine();
                            emitter().emit("// -- register event call back ");
                            emitter().emit("on_completion(dev->read_buffer_event[%d], " +
                                    "&dev->read_buffer_event_info[%1$d]);", entity.getOutputPorts().indexOf(port));
                            emitter().emitNewLine();
                            emitter().decreaseIndentation();
                        }
                        emitter().decreaseIndentation();
                    }

                    emitter().decreaseIndentation();
                } emitter().emit("} else {");
                {
                    emitter().increaseIndentation();
                    OclMsg("Info: port %s did not produce any data, skipping read transfer.\\n", port.getName());
                    emitter().decreaseIndentation();
                }
                emitter().emit("}");
            }
            emitter().decreaseIndentation();
        }
        emitter().emit("}");

    }
    default void getReleaseMemObjects(PartitionLink entity) {
        Method method = getMethod(entity.getHandle(), "releaseMemObjects");
        emitter().emit("%s {", methodSignature(entity.getHandle(), method));
        {
            emitter().increaseIndentation();
            Stream.concat(entity.getInputPorts().stream(), entity.getOutputPorts().stream())
                    .forEach(this::getReleaseMemObject);
            emitter().decreaseIndentation();
        }
        emitter().emit("}");
    }

    default void getReleaseMemObject(PortDecl port) {
        emitter().emitNewLine();
        OclMsg("Releasing mem object %s_cl_buffer\\n", port.getName());
        OclCheck("clReleaseMemObject(dev->%s_cl_buffer)", port.getName());
        OclMsg("Releasing mem object %s_cl_size\\n", port.getName());
        OclCheck("clReleaseMemObject(dev->%s_cl_size)", port.getName());
        emitter().emitNewLine();
    }

    default void getSetAndGetPtrs(PartitionLink entity) {
        emitter().emit("// -- get pointer methods");
        Stream.concat(entity.getInputPorts().stream(), entity.getOutputPorts().stream())
                .forEachOrdered(p -> getGetPtr(entity.getHandle(), p));

        emitter().emitNewLine();
        emitter().emit(" // -- set pointer methods");
        Stream.concat(entity.getInputPorts().stream(), entity.getOutputPorts().stream())
                .forEachOrdered(p -> getSetPtr(entity.getHandle(), p));

        emitter().emitNewLine();

    }

    default void getGetPtr(PartitionHandle handle, PortDecl port) {
        Method getBuffmethod = getMethod(handle, "get_" + port.getName() + "_buffer_ptr");

        emitter().emitNewLine();
        emitter().emit("// -- get pointers for %s", port.getName());
        emitter().emit("%s { return dev->%s_buffer; }",
                methodSignature(handle, getBuffmethod), port.getName());

        Method getSizemethod = getMethod(handle, "get_" + port.getName() + "_size_ptr");
        emitter().emit("%s { return dev->%s_size; }",
                methodSignature(handle, getSizemethod), port.getName());
        emitter().emitNewLine();
    }

    default void getSetPtr(PartitionHandle handle, PortDecl port) {
        Method setBuffMethod = getMethod(handle, "set_" + port.getName() + "_buffer_ptr");
        Method setSizeMethod = getMethod(handle, "set_" + port.getName() + "_size_ptr");
        emitter().emitNewLine();
        emitter().emit("// -- set pointers for %s", port.getName());
        emitter().emit("%s { dev->%s_buffer = ptr; }",
                methodSignature(handle, setBuffMethod), port.getName());
        emitter().emitNewLine();
        emitter().emit("%s { dev->%s_size = ptr; }",
                methodSignature(handle, setSizeMethod), port.getName());
        emitter().emitNewLine();
    }

    default void getSetRequestSize(PartitionHandle handle, PortDecl port) {
        Method setReq = getMethod(handle, "set_" + port.getName() + "_request_size");
        emitter().emitNewLine();
        emitter().emit("// -- set request size for %s", port.getName());
        emitter().emit("%s { dev->%s_request_size = req_sz; }", methodSignature(handle, setReq),
                port.getName());
        emitter().emitNewLine();
    }
    /**
     * Generates the header file device-handle.h
     * @param entity the PartitionLink entity for which the header file is generated
     */
    default void generateDeviceHandleHeader(PartitionLink entity) {

        // -- open output file
        Path artPlinkPath = PathUtils.createDirectory(PathUtils.getTargetLib(backend().context()), "art-plink");
        emitter().open(artPlinkPath.resolve("device-handle.h"));
        String networkId = backend().task().getIdentifier().getLast().toString();

        emitter().emit("#ifndef DEVICE_HANDLE_H", networkId.toUpperCase());
        emitter().emit("#define DEVICE_HANDLE_H", networkId.toUpperCase());
        // -- header includes
        getIncludes();
        // -- defines
        getDefines(entity);
        // -- OCL macros
        getOclMacros();
        // -- get util structures
        getUtilStructs();
        // -- get device handle struct
        getDeviceHandleStruct(entity);
        emitter().emit("#endif // DEVICE_HANDLE_H", networkId.toUpperCase());
        emitter().close();
    }

    default void getIncludes() {
        ImmutableList<String> includes =
                ImmutableList.of(
                        "CL/cl_ext.h",
                        "CL/opencl.h",
                        "assert.h",
                        "fcntl.h",
                        "math.h",
                        "stdbool.h",
                        "stdio.h",
                        "stdlib.h",
                        "string.h",
                        "sys/stat.h",
                        "sys/time.h",
                        "sys/types.h",
                        "unistd.h");
        includes.forEach(i -> emitter().emit("#include <%s>", i));
        emitter().emitNewLine();
    }

    default void getDefines(PartitionLink entity) {

        emitter().emitNewLine();
        emitter().emit("// -- OpenCL and actor specific defines");
        emitter().emit("#define OCL_VERBOSE\t\t\t1");
        emitter().emit("#define OCL_ERROR\t\t\t1");
        emitter().emit("#define %s\t\t\t4096", memAlignment());
        emitter().emit("#define BUFFER_SIZE (1 << 16)");
        emitter().emit("#define NUM_INPUTS\t\t\t%d", entity.getInputPorts().size());
        emitter().emit("#define NUM_OUTPUTS\t\t\t%s", entity.getOutputPorts().size());
        emitter().emitNewLine();

    }

    default void getOclMacros() {
        // -- OCL_CHECK
        emitter().emit("// -- helper macros");
        emitter().emitNewLine();
        emitter().emit("#define OCL_CHECK(call)\t\t\\");
        emitter().increaseIndentation();
        {
            emitter().emit("do {\t\t\\");
            emitter().increaseIndentation();
            {
                emitter().emit("cl_int err = call;\t\t\\");
                emitter().emit("if (err != CL_SUCCESS) { \t\t\\");
                emitter().increaseIndentation();
                {
                    emitter().emit("fprintf(stderr, \"Error calling\" #call \", error code is: %%d\", err);\t\t\\");
                    emitter().emit("exit(EXIT_FAILURE);\t\t\\");
                    emitter().emit("}\t\t\\");
                }
                emitter().decreaseIndentation();
                emitter().emit("} while (0);");
            }
            emitter().decreaseIndentation();
        }
        emitter().decreaseIndentation();
        emitter().emitNewLine();
        // -- OCL MSG
        emitter().emit("#define OCL_MSG(fmt, args...)\t\t\\");
        {
            emitter().increaseIndentation();
            emitter().emit("do {\t\t\\");
            {
                emitter().increaseIndentation();
                emitter().emit("if (OCL_VERBOSE)\t\t\\");
                emitter().emit("\tfprintf(stdout, \"OCL_MSG:%%s():%%d: \" fmt, __func__, __LINE__, ##args);\\");
                emitter().emit("} while (0);");
                emitter().decreaseIndentation();
            }
            emitter().decreaseIndentation();
        }
        emitter().emitNewLine();
        // -- OCL ERR
        emitter().emit("#define OCL_ERR(fmt, args...)\t\t\\");
        {
            emitter().increaseIndentation();
            emitter().emit("do {\t\t\\");
            {
                emitter().increaseIndentation();
                emitter().emit("if (OCL_ERROR)\t\t\\");
                emitter().emit("\tfprintf(stderr, \"OCL_ERR:%%s():%%d: \" fmt, __func__, __LINE__, ##args);\\");
                emitter().emit("} while (0);");
                emitter().decreaseIndentation();
            }
            emitter().decreaseIndentation();
        }
    }

    default void getUtilStructs() {

        emitter().emitNewLine();
        emitter().emit("// -- event handling info");
        emitter().emit("typedef struct EventInfo {");
        {
            emitter().increaseIndentation();
            emitter().emit("%s counter;", defaultSizeType());
            emitter().emit("char msg[128];");
            emitter().decreaseIndentation();
        }
        emitter().emit("} EventInfo;");
        emitter().emitNewLine();
        emitter().emit("typedef struct OCLWorld {");
        {
            emitter().increaseIndentation();

            emitter().emit("cl_context context;");
            emitter().emit("cl_platform_id platform_id;");
            emitter().emit("cl_device_id device_id;");
            emitter().emit("cl_command_queue command_queue;");

            emitter().decreaseIndentation();
        }
        emitter().emit("} OCLWorld;");
        emitter().emitNewLine();

    }

    default void getDeviceHandleStruct(PartitionLink entity) {

        emitter().emit("// -- Device Handle struct");
        emitter().emit("typedef struct DeviceHandle_t {");
        {
            emitter().increaseIndentation();
            getDeviceHandleFields(entity);
            emitter().decreaseIndentation();

        }
        emitter().emit("} DeviceHandle_t;");

        emitter().emitNewLine();
        emitter().emit("// -- method declarations");
        getMethodDeclarations(entity);

    }


    default void getMethodDeclarations(PartitionLink entity) {

        // -- emit constructor
        emitMethodDecl(entity.getHandle(), entity.getHandle().getConstructor());
        // -- emit destructor
        emitMethodDecl(entity.getHandle(), entity.getHandle().getDestructor());
        // -- the rest of methods
        entity.getHandle().getMethods().stream().forEachOrdered(m -> emitMethodDecl(entity.getHandle(), m));
    }

    default void emitMethodDecl(PartitionHandle handle, Method method) {
        String name = methodName(handle, method);
        emitter().emitNewLine();
        emitter().emit("%s;", methodSignature(handle, method));
        emitter().emitNewLine();
    }


    default String methodArgs(PartitionHandle handle, Method method) {

        if(method.isGlobal()) {
            return
                String.join(", ", method.getArgs().stream().map(this::evalArg).collect(Collectors.toList()));
        } else {
            Pair<Type, String> thisArg = Method.MethodArg(Type.of(handleClassName(handle), true), "dev");
            ImmutableList<Pair<Type, String>> xArgs =
                    ImmutableList.<Pair<Type, String>>builder().add(thisArg)
                            .addAll(method.getArgs()).build();
            return
                String.join(", ", xArgs.stream().map(this::evalArg).collect(Collectors.toList()));

        }
    }



    default void getDeviceHandleFields(PartitionLink entity) {

        entity.getHandle().getFields().stream().forEachOrdered(f -> emitField(entity.getHandle(), f));

    }

    default void emitField(PartitionHandle handle, Field field) {
        emitter().emitNewLine();
        if (!field.getDescription().isEmpty()) {
            emitter().emit("// -- %s", field.getDescription());
        }
        String name = field.getType().isReference() ? "*" + field.getName() : field.getName();
        String type = evalType(field.getType());
        emitter().emit("%s %s;", type, name);
        emitter().emitNewLine();
    }
}