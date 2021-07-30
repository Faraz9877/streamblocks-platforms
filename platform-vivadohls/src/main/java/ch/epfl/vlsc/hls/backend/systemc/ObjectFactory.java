package ch.epfl.vlsc.hls.backend.systemc;

public class ObjectFactory {

    public static LogicVector getNewLogicVector(int width) {
        if(SystemCNetwork.useVerilator())
            return new LogicVectorVerilator(width);
        else
            return new LogicVectorHLS(width);
    }

}
