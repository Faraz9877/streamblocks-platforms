package ch.epfl.vlsc.hls.backend.systemc;

public class LogicVectorHLS extends LogicVector{



    public LogicVectorHLS(int width) {
        super(width);

    }

    @Override
    public String getType() {
        if (getWidth() == 1) {
            return "sc_logic";
        } else {
            return " sc_lv<" + getWidth() + "> ";
        }
    }
}
