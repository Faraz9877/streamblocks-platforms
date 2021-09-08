
# CMakeLists to generate Systemc simulator

function(copy_dat_ram_files ACTOR)
  add_custom_command(
    TARGET simulate art-systemc PRE_BUILD
    COMMAND cp
      ${VERILOG_GEN_DIR}/${ACTOR}/solution/syn/verilog/*.dat
      ${EXECUTABLE_OUTPUT_PATH} 2> /dev/null || true
    DEPENDS ${ACTOR}
    COMMENT "Copying ram files for ${ACTOR}"
  )
endfunction()

set(CMAKE_PREFIX_PATH ${CMAKE_PREFIX_PATH} /opt/systemc)

set(EXECUTABLE_OUTPUT_PATH ${CMAKE_SOURCE_DIR}/bin)

message(STATUS "Using VivadoHLS")

macro(generate_actor_includes ACTOR)
  set(ART_SYSTEMC_HEADERS
    ${ART_SYSTEMC_HEADERS}
    ${VERILOG_GEN_DIR}/${ACTOR}/solution/syn # /systemc is to be specified in actor headers
  )
endmacro()

add_custom_target(copy_all_systemc_sources)
macro(generate_actor_sources ACTOR)
  set(ART_SYSTEMC_SOURCES
    ${ART_SYSTEMC_SOURCES}
    # ${CMAKE_CURRENT_BINARY_DIR}/${ACTOR}.cpp
    ${VERILOG_GEN_DIR}/${ACTOR}/solution/syn/systemc/${ACTOR}.cpp
  )
  add_custom_command(
    OUTPUT ${VERILOG_GEN_DIR}/${ACTOR}/solution/syn/systemc/${ACTOR}.cpp 
    DEPENDS ${ACTOR}
  )
  # Mahyar added for individual actor class making
  message(STATUS "This actor's source file is in: ${CMAKE_CURRENT_BINARY_DIR}/${ACTOR}.cpp")
  # add_library(${ACTOR}_compiled_sc ${VERILOG_GEN_DIR}/${ACTOR}/solution/syn/systemc/${ACTOR}.cpp)
  
  # add_dependencies(${ACTOR}_compiled_sc ${ACTOR})
  # target_include_directories(${ACTOR}_compiled_sc PUBLIC  
  #   ${VERILOG_GEN_DIR}/${ACTOR}/solution/syn/systemc 
  #   /scratch/faraz/missingLibs/
  #   /opt/systemc/include)
  # set_target_properties(${ACTOR}_compiled_sc PROPERTIES
  # 	CXX_STANDARD 14
  # 	CXX_STANDARD_REQUIRED YES
  # 	CXX_EXTENSIONS NO
  # )
endmacro()

set(ART_SYSTEMC_HEADERS
  ${ART_SYSTEMC_HEADERS}
  ${VIVADO_HLS_INCLUDE_DIRS}
  ${PROJECT_SOURCE_DIR}/code-gen/include
  ${PROJECT_SOURCE_DIR}/systemc/include
  /scratch/faraz/missingLibs/ # missingLibs/ Is to be specified in the codes
  /opt/systemc/include
)

foreach(__ACTOR__ ${__ACTORS_IN_NETWORK__})
  generate_actor_includes(${__ACTOR__})
  generate_actor_sources(${__ACTOR__})
endforeach()

add_library(art-systemc STATIC ${ART_SYSTEMC_SOURCES} src/trigger.cpp src/profiler.cpp)

MESSAGE(STATUS "${ART_SYSTEMC_SOURCES}")

target_include_directories(art-systemc PUBLIC ${ART_SYSTEMC_HEADERS})

set_target_properties(art-systemc PROPERTIES
  CXX_STANDARD 14
  CXX_STANDARD_REQUIRED YES
  CXX_EXTENSIONS NO
)

add_executable(simulate src/simulate.cpp)

target_include_directories(simulate PUBLIC ${ART_SYSTEMC_HEADERS})

target_link_libraries(simulate art-systemc)

set_target_properties(simulate PROPERTIES
  CXX_STANDARD 14
  CXX_STANDARD_REQUIRED YES
  CXX_EXTENSIONS NO
)

foreach(__ACTOR__ ${__ACTORS_IN_NETWORK__})
  copy_dat_ram_files(${__ACTOR__})
endforeach()

find_package(SystemCLanguage CONFIG REQUIRED)
target_link_libraries(simulate SystemC::systemc)