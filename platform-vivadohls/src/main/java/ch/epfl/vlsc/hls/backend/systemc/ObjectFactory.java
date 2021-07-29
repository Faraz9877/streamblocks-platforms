package ch.epfl.vlsc.hls.backend.systemc;

import se.lth.cs.tycho.reporting.CompilationException;
import se.lth.cs.tycho.reporting.Diagnostic;

public class ObjectFactory {

    public static LogicVector getNewLogicVector(int width) {
        if(SystemCNetwork.useVerilator())
            return new LogicVectorVerilator(width);
        else
            return new LogicVectorHLS(width);
    }

}
