package ch.epfl.vlsc.sw.backend;

import ch.epfl.vlsc.platformutils.Emitter;
import ch.epfl.vlsc.platformutils.PathUtils;
import ch.epfl.vlsc.settings.PlatformSettings;
import org.multij.Binding;
import org.multij.BindingKind;
import org.multij.Module;
import se.lth.cs.tycho.ir.decl.GlobalEntityDecl;
import se.lth.cs.tycho.ir.network.Instance;

@Module
public interface CMakeLists {

    @Binding(BindingKind.INJECTED)
    MulticoreBackend backend();

    default Emitter emitter() {
        return backend().emitter();
    }

    default void projectCMakeLists() {
        emitter().open(PathUtils.getTarget(backend().context()).resolve("CMakeLists.txt"));
        // -- CMake Minimal version
        emitter().emit("# -- Generated from %s", backend().task().getIdentifier());
        emitter().emit("cmake_minimum_required(VERSION 3.10)");
        emitter().emitNewLine();

        // -- Project name
        emitter().emit("project (%s-multicore)", backend().task().getIdentifier().getLast().toString());
        emitter().emitNewLine();

        // -- C Standard
        emitter().emit("set(CMAKE_C_STANDARD 11)");
        emitter().emitNewLine();

        // -- ART Node
        emitter().emit("option(ART_NODE \"Run actors on ART Node.\" OFF)");
        emitter().emitNewLine();

        // -- Default C Flags
        emitter().emit("# -- Default C Flags");
        emitter().emit("set(CMAKE_C_FLAGS \"-Wall -Wno-unused-variable -Wno-missing-braces\")");
        emitter().emitNewLine();

        // -- Binary output folder
        emitter().emit("# -- Configure output Folder for generated binary");
        emitter().emit("set(EXECUTABLE_OUTPUT_PATH ${CMAKE_SOURCE_DIR}/bin)");
        emitter().emitNewLine();

        // -- Definitions used in sub directories
        emitter().emit("# -- Definitions used in sub directories");
        emitter().emit("set(extra_definitions)");
        emitter().emit("set(extra_includes)");
        emitter().emitNewLine();

        // -- Include directories
        emitter().emit("# -- Include directories");

        emitter().emit("if(ART_NODE)");
        {
            emitter().increaseIndentation();

            emitter().emit("include_directories(");
            emitter().increaseIndentation();
            emitter().emit("lib/art-node/include");
            emitter().emit("lib/art-native/include");
            emitter().decreaseIndentation();
            emitter().emit(")");

            emitter().decreaseIndentation();
        }
        boolean hasPlink = backend().context().getConfiguration().isDefined(PlatformSettings.PartitionNetwork)
                && backend().context().getConfiguration().get(PlatformSettings.PartitionNetwork);
        emitter().emit("else()");
        {
            emitter().increaseIndentation();

            emitter().emit("include_directories(");
            emitter().increaseIndentation();
            emitter().emit("lib/art-runtime/include");
            emitter().emit("lib/art-native/include");
            if(hasPlink)
                emitter().emit("lib/art-plink");
            emitter().decreaseIndentation();
            emitter().emit(")");

            emitter().decreaseIndentation();
        }
        emitter().emit("endif()");
        emitter().emitNewLine();

        // -- Add sub directories
        emitter().emit("# -- Add sub directories ");
        emitter().emit("add_subdirectory(lib)");
        emitter().emit("add_subdirectory(code-gen)");
        emitter().emitNewLine();

        // -- EOF
        emitter().close();
    }

    default void codegenCMakeLists() {
        emitter().open(PathUtils.getTargetCodeGen(backend().context()).resolve("CMakeLists.txt"));
        emitter().emit("# -- Generated from %s", backend().task().getIdentifier());
        emitter().emitNewLine();

        // -- CodeGen sources
        emitter().emit("# -- Generated code source files");
        emitter().emit("set(multicore_sources");
        emitter().increaseIndentation();

        for (Instance instance : backend().task().getNetwork().getInstances()) {
            GlobalEntityDecl entityDecl = backend().globalnames().entityDecl(instance.getEntityName(), true);
            if (!entityDecl.getExternal()) {
                String filename = backend().instaceQID(instance.getInstanceName(), "_") + ".cc";
                emitter().emit("src/%s", filename);
            }
        }

        // -- Add main
        emitter().emit("src/globals.cc");
        emitter().emit("src/main.cc");

        emitter().decreaseIndentation();
        emitter().emit(")");
        emitter().emitNewLine();

        // -- Generated code headers
        emitter().emit("# -- Generated code headers");
        emitter().emit("set(multicore_headers");
        emitter().increaseIndentation();
        emitter().emit("include/__arrayCopy.h");
        emitter().emit("include/globals.h");
        emitter().decreaseIndentation();
        emitter().emit(")");
        emitter().emitNewLine();

        // -- Include directories
        emitter().emit("# -- Include directories");
        emitter().emit("include_directories(${extra_includes} ./include)");
        emitter().emitNewLine();

        // -- Add definitions
        emitter().emit("add_definitions(${extra_definitions})");
        emitter().emitNewLine();

        // -- Node or Executable
        emitter().emit("## -- Node or executable");
        emitter().emit("if(ART_NODE)");
        {
            emitter().increaseIndentation();

            emitter().emit("# -- Shared Module flags");
            emitter().emit("set(CMAKE_SHARED_MODULE_CREATE_C_FLAGS \"${CMAKE_SHARED_MODULE_CREATE_C_FLAGS} -std=c99 -Wall -Wno-parentheses-equality -fPIC -flat_namespace -bundle -undefined suppress\")");
            emitter().emitNewLine();

            emitter().emit("# -- Shared Module for each actor");
            for (Instance instance : backend().task().getNetwork().getInstances()) {
                GlobalEntityDecl entityDecl = backend().globalnames().entityDecl(instance.getEntityName(), true);
                if (!entityDecl.getExternal()) {
                    String name = backend().instaceQID(instance.getInstanceName(), "_");

                    emitter().emit("add_library(%s MODULE src/%1$s.c src/globals.c)", name);
                    emitter().emit("set_target_properties(%s PROPERTIES COMPILE_FLAGS \"-Wall -fPIC\")", name);
                    emitter().emit("set_target_properties(%s PROPERTIES PREFIX \"\")", name);
                    emitter().emit("set_target_properties(%s PROPERTIES LIBRARY_OUTPUT_DIRECTORY \"${CMAKE_SOURCE_DIR}/bin/modules\")", name);
                    emitter().emit("if(APPLE)");
                    emitter().increaseIndentation();
                    emitter().emit("set_target_properties(%s PROPERTIES SUFFIX \".bundle\")", name);
                    emitter().decreaseIndentation();
                    emitter().emit("endif()");
                    emitter().emit("target_link_libraries(%s art-node ${extra_libraries})", name);

                    emitter().emitNewLine();
                }
            }

            emitter().decreaseIndentation();
        }
        emitter().emit("else()");
        {
           emitter().increaseIndentation();

            // -- Add executable
            emitter().emit("# -- Add executable");
            emitter().emit("add_executable(%s ${multicore_sources})", backend().task().getIdentifier().getLast().toString());
            emitter().emitNewLine();

            emitter().emit("if (USE_SYSTEMC)");
            {
                emitter().increaseIndentation();
                // -- find packages
                emitter().emit("set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} ${CMAKE_SOURCE_DIR}/cmake)");
                emitter().emit("find_package(SystemCLanguage)");
                emitter().emit("find_package(Verilator)");
                emitter().emitNewLine();
                emitter().emit("set(extra_systemc_headers");
                {
                    emitter().increaseIndentation();
                    emitter().emit("${SYSTEMC_PATH}");
                    emitter().emit("${VERILATOR_STD_INCLUDE_DIR}");
                    emitter().emit("${VERILATOR_INCLUDE_DIR}");
                    emitter().emit("${CMAKE_BINARY_DIR}/vivado-hls/verilated/");
                    emitter().emit("${CMAKE_SOURCE_DIR}/vivado-hls/code-gen/include");
                    emitter().decreaseIndentation();
                }
                emitter().emit(")");

                emitter().emit("set_target_properties(%s PROPERTIES", backend().task().getIdentifier().getLast().toString());
                {
                    emitter().increaseIndentation();
                    emitter().emit("CXX_STANDARD 14");
                    emitter().emit("CXX_STANDARD_REQUIRED YES");
                    emitter().emit("CXX_EXTENSIONS NO");
                    emitter().decreaseIndentation();
                }
                emitter().emit(")");
                emitter().decreaseIndentation();
            }
            emitter().emit("endif()");
            // -- Target Include directories
            emitter().emit("# -- Target include directories");
            emitter().emit("target_include_directories(%s PRIVATE ./include ${extra_systemc_headers})", backend().task().getIdentifier().getLast().toString());
            emitter().emitNewLine();

            boolean hasPlink =
                    backend().context().getConfiguration().isDefined(PlatformSettings.PartitionNetwork) &&
                            backend().context().getConfiguration().get(PlatformSettings.PartitionNetwork);
            boolean isSimulated =
                    backend().context().getConfiguration().isDefined(PlatformSettings.enableSystemC) &&
                            backend().context().getConfiguration().get(PlatformSettings.enableSystemC);

            // -- Target link libraries
            emitter().emit("# -- Target link libraries");
            emitter().emit("target_link_libraries(%s art-genomic art-native art-runtime %s ${extra_libraries})",
                    backend().task().getIdentifier().getLast().toString(),
                    hasPlink && isSimulated ? "art-systemc" :
                            hasPlink && !isSimulated ? "art-plink" : "");
            emitter().decreaseIndentation();
        }
        emitter().emit("endif()");

        // -- EOF
        emitter().close();
    }


    default void codegenNodeCCCMakeLists() {
        emitter().open(PathUtils.getTargetCodeGenCC(backend().context()).resolve("CMakeLists.txt"));
        emitter().emit("# -- Generated from %s", backend().task().getIdentifier());
        emitter().emitNewLine();

        // -- Include directories
        emitter().emit("# -- Include directories");
        emitter().emit("include_directories(${extra_includes} ./include)");
        emitter().emitNewLine();

        // -- Add definitions
        emitter().emit("add_definitions(${extra_definitions})");
        emitter().emitNewLine();

        emitter().emitSharpBlockComment("Shared Module for each actor");
        emitter().emitNewLine();

        for (Instance instance : backend().task().getNetwork().getInstances()) {
            GlobalEntityDecl entityDecl = backend().globalnames().entityDecl(instance.getEntityName(), true);
            if (!entityDecl.getExternal()) {
                String name = backend().instaceQID(instance.getInstanceName(), "_");
                emitter().emit("# -- Actor : %s", name);
                emitter().emit("add_library(%s MODULE src/%1$s.cc src/globals.cc)", name);
                emitter().emit("set_target_properties(%s PROPERTIES PREFIX \"\")", name);
                emitter().emit("set_target_properties(%s PROPERTIES LIBRARY_OUTPUT_DIRECTORY \"${CMAKE_SOURCE_DIR}/bin/modules\")", name);
                emitter().emit("if(MSVC)");
                {
                    emitter().increaseIndentation();
                    emitter().emit("set_target_properties(%s PROPERTIES COMPILE_FLAGS \"/std:c++latest\")", name);
                    emitter().emit("set_target_properties(%s PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)", name);
                    emitter().decreaseIndentation();
                }
                emitter().emit("elseif(APPLE)");
                {
                    emitter().increaseIndentation();
                    emitter().emit("set_target_properties(%s PROPERTIES SUFFIX \".bundle\")", name);
                    emitter().decreaseIndentation();
                }
                emitter().emit("else()");
                {
                    emitter().increaseIndentation();
                    emitter().emit("set_target_properties(%s PROPERTIES COMPILE_FLAGS \"-Wall -fPIC\")", name);
                    emitter().decreaseIndentation();
                }
                emitter().emit("endif()");

                emitter().emit("target_link_libraries(%s art-node art-native ${extra_libraries})", name);

                emitter().emitNewLine();
            }
        }


        // -- EOF
        emitter().close();
    }

}
