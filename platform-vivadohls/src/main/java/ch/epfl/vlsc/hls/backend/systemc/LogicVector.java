package ch.epfl.vlsc.hls.backend.systemc;

import se.lth.cs.tycho.reporting.CompilationException;
import se.lth.cs.tycho.reporting.Diagnostic;

public abstract class LogicVector implements SCType{

    private final int width;

    public LogicVector(int width) {
        this.width = width;
    }

    public int getWidth() {
        return this.width;
    }

    public abstract String getType() ;
}
