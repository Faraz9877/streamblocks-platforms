package ch.epfl.vlsc.hls.backend.systemc;

public class LogicValue implements SCType {

    public enum Value {
        SC_LOGIC_0,
        SC_LOGIC_1;
        @Override
        public String toString() {
            switch (this) {
                case SC_LOGIC_0:
                    if(SystemCNetwork.useVerilator())
                        return "false";
                    else
                        return "SC_LOGIC_0";
                case SC_LOGIC_1:
                    if(SystemCNetwork.useVerilator())
                        return "true";
                    else
                        return "SC_LOGIC_1";
                default:
                    return "ERROR";
            }
        }
    };
    @Override
    public String getType() {
        if(SystemCNetwork.useVerilator())
            return "bool";
        else
            return "sc_logic";
    }

}
