package ch.epfl.vlsc.hls.backend.systemc;

import se.lth.cs.tycho.reporting.CompilationException;
import se.lth.cs.tycho.reporting.Diagnostic;

public class LogicVectorHLS extends LogicVector{



    public LogicVectorHLS(int width) {
        super(width);

    }

    @Override
    public String getType() {
        return " sc_lv<" + getWidth() + "> ";
    }
}
