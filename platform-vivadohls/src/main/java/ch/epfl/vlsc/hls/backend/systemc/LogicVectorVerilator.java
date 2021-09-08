package ch.epfl.vlsc.hls.backend.systemc;

import se.lth.cs.tycho.reporting.CompilationException;
import se.lth.cs.tycho.reporting.Diagnostic;

public class LogicVectorVerilator extends LogicVector {


    public LogicVectorVerilator(int width) {
        super(width);
    }

   
    @Override
    public String getType() {
        if (getWidth() == 1) {
            return "bool";
        } else if (getWidth() <= 32) {
            return "uint32_t";
        } else if (getWidth() <= 64) {
            return "uint64_t";
        } else {
            return " sc_bv<" + getWidth() + "> ";
        }

    }
}
