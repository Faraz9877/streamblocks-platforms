package ch.epfl.vlsc.sw.backend;

import ch.epfl.vlsc.platformutils.Emitter;
import org.multij.Binding;
import org.multij.BindingKind;
import org.multij.Module;
import se.lth.cs.tycho.attribute.Types;
import se.lth.cs.tycho.ir.decl.GeneratorVarDecl;
import se.lth.cs.tycho.ir.decl.VarDecl;
import se.lth.cs.tycho.ir.expr.ExprBinaryOp;
import se.lth.cs.tycho.ir.expr.ExprInput;
import se.lth.cs.tycho.ir.expr.ExprVariable;
import se.lth.cs.tycho.ir.expr.Expression;
import se.lth.cs.tycho.ir.stmt.*;
import se.lth.cs.tycho.ir.stmt.lvalue.LValueVariable;
import se.lth.cs.tycho.type.AlgebraicType;
import se.lth.cs.tycho.type.ListType;
import se.lth.cs.tycho.type.Type;

import java.util.*;
import java.util.stream.Collectors;

@Module
public interface Statements {
    @Binding(BindingKind.INJECTED)
    MulticoreBackend backend();

    default Emitter emitter() {
        return backend().emitter();
    }

    default ExpressionEvaluator expressioneval() {
        return backend().expressionEval();
    }

    default LValues lvalues() {
        return backend().lvalues();
    }

    default Types types() {
        return backend().types();
    }

    default Variables variables() {
        return backend().variables();
    }

    default Declarations declarartions() {
        return backend().declarations();
    }

    default TypesEvaluator typeseval() {
        return backend().typesEval();
    }

    default ChannelsUtils channelsutils() {
        return backend().channelsutils();
    }

    default MemoryStack memoryStack() {
        return backend().memoryStack();
    }

    void execute(Statement stmt);

    /*
     * Statement Consume
     */

    default void execute(StmtConsume consume) {
        if (consume.getNumberOfTokens() > 1) {
            emitter().emit("pinConsumeRepeat_%s(%s, %d);", channelsutils().inputPortTypeSize(consume.getPort()), channelsutils().definedInputPort(consume.getPort()), consume.getNumberOfTokens());
        } else {
            emitter().emit("pinConsume_%s(%s);", channelsutils().inputPortTypeSize(consume.getPort()), channelsutils().definedInputPort(consume.getPort()));
        }
    }

    /*
     * Statement Write
     */

    default void execute(StmtWrite write) {
        if (write.getRepeatExpression() == null) {
            Type type = types().portType(write.getPort());
            String portType;
            if (type instanceof AlgebraicType) {
                portType = "ref";

            } else {
                portType = typeseval().type(type);

            }
            String tmp = variables().generateTemp();
            emitter().emit("%s = %s;", declarartions().declaration(types().portType(write.getPort()), tmp), backend().defaultValues().defaultValue(type));
            for (Expression expr : write.getValues()) {
                if(expr instanceof ExprVariable){
                    backend().memoryStack().untrackPointer(expressioneval().evaluate(expr));
                }
                emitter().emit("%s = %s;", tmp, expressioneval().evaluate(expr));
                emitter().emit("pinWrite_%s(%s, %s);", portType, channelsutils().definedOutputPort(write.getPort()), tmp);
            }
        } else if (write.getValues().size() == 1) {
            Type valueType = types().type(write.getValues().get(0));
            Type portType = channelsutils().outputPortType(write.getPort());
            String value = expressioneval().evaluate(write.getValues().get(0));
            String repeat = expressioneval().evaluate(write.getRepeatExpression());

            // -- Hack type conversion : to be fixed
            if (valueType instanceof ListType) {
                ListType listType = (ListType) valueType;
                if (!listType.getElementType().equals(portType)) {
                    String index = variables().generateTemp();
                    emitter().emit("for (size_t %1$s = 0; %1$s < (%2$s); %1$s++) {", index, repeat);
                    emitter().emit("\tpinWrite_%s(%s, %s[%s]);", channelsutils().outputPortTypeSize(write.getPort()), channelsutils().definedOutputPort(write.getPort()), value, index);
                    emitter().emit("}");
                } else {
                    emitter().emit("pinWriteRepeat_%s(%s, %s, %s);", channelsutils().outputPortTypeSize(write.getPort()), channelsutils().definedOutputPort(write.getPort()), value, repeat);
                }
            } else {
                emitter().emit("pinWriteRepeat_%s(%s, %s, %s);", channelsutils().outputPortTypeSize(write.getPort()), channelsutils().definedOutputPort(write.getPort()), value, repeat);
            }

        } else {
            throw new Error("not implemented");
        }
    }

    /*
     * Statement Assign
     */

    default void execute(StmtAssignment assign) {
        memoryStack().enterScope();
        Type type = types().lvalueType(assign.getLValue());
        String lvalue = lvalues().lvalue(assign.getLValue());
        if (type instanceof ListType && assign.getLValue() instanceof LValueVariable) {
            expressioneval().evaluate(assign.getExpression());
        } else {
            copy(type, lvalue, types().type(assign.getExpression()), expressioneval().evaluate(assign.getExpression()));
        }
        memoryStack().exitScope();
    }

    default void copy(Type lvalueType, String lvalue, Type rvalueType, String rvalue) {
        emitter().emit("%s = %s;", lvalue, rvalue);
    }

    default void copy(ListType lvalueType, String lvalue, ListType rvalueType, String rvalue) {
        //if (!lvalueType.equals(rvalueType)) {
        String maxIndex = typeseval().sizeByDimension(lvalueType).stream().map(Object::toString).collect(Collectors.joining(" * "));
        String index = variables().generateTemp();
        emitter().emit("for (size_t %1$s = 0; %1$s < (%2$s); %1$s++) {", index, maxIndex);
        emitter().increaseIndentation();
        emitter().emit("%s[%s] = %s[%2$s];", lvalue, index, rvalue);
        emitter().decreaseIndentation();
        emitter().emit("}");
        //}
    }

    default void copy(AlgebraicType lvalueType, String lvalue, AlgebraicType rvalueType, String rvalue) {
        emitter().emit("copyStruct%s(&(%s), %s);", backend().algebraic().type(lvalueType), lvalue, rvalue);
    }

    /*
     * Statement Call
     */

    default void execute(StmtCall call) {
        memoryStack().enterScope();
        String proc;
        List<String> parameters = new ArrayList<>();
        boolean directlyCallable = backend().callablesInActor().directlyCallable(call.getProcedure());
/*
        if (directlyCallable.isPresent()) {
            proc = directlyCallable.get();
            parameters.add("NULL");
        } else {
            String name = expressioneval().evaluate(call.getProcedure());
            proc = name + ".f";
            parameters.add(name + ".env");
        }*/

        if (!directlyCallable) {
            parameters.add("thisActor");
        }
        proc = expressioneval().evaluateCall(call.getProcedure());

        for (Expression parameter : call.getArgs()) {
            parameters.add(expressioneval().evaluate(parameter));
        }
        emitter().emit("%s(%s);", proc, String.join(", ", parameters));
        memoryStack().exitScope();
    }

    /*
     * Statement Block
     */
    default void execute(StmtBlock block) {
        emitter().emit("{");
        emitter().increaseIndentation();
        memoryStack().enterScope();
        for (VarDecl decl : block.getVarDecls()) {

            Type t = types().declaredType(decl);
            String declarationName = variables().declarationName(decl);
            if (t instanceof AlgebraicType) {
                memoryStack().trackPointer(declarationName, t);
            }
            String d = declarartions().declarationTemp(t, declarationName);
            emitter().emit("%s = %s;", d, backend().defaultValues().defaultValue(t));
            if (decl.getValue() != null) {
                if (decl.getValue() instanceof ExprInput) {
                    expressioneval().evaluateWithLvalue(backend().variables().declarationName(decl), (ExprInput) decl.getValue());
                } else {
                    copy(t, declarationName, types().type(decl.getValue()), expressioneval().evaluate(decl.getValue()));
                }
            }

        }

        block.getStatements().forEach(this::execute);
        memoryStack().exitScope();
        emitter().decreaseIndentation();
        emitter().emit("}");
    }

    /*
     * Statement If
     */

    default void execute(StmtIf stmt) {
        memoryStack().enterScope();
        emitter().emit("if (%s) {", expressioneval().evaluate(stmt.getCondition()));
        emitter().increaseIndentation();
        memoryStack().enterScope();
        stmt.getThenBranch().forEach(this::execute);
        memoryStack().exitScope();
        emitter().decreaseIndentation();
        if (stmt.getElseBranch() != null) {
            if (stmt.getElseBranch().size() > 0) {
                emitter().emit("} else {");
                emitter().increaseIndentation();
                memoryStack().enterScope();
                stmt.getElseBranch().forEach(this::execute);
                memoryStack().exitScope();
                emitter().decreaseIndentation();
            }
        }
        emitter().emit("}");
        memoryStack().exitScope();
    }

    /*
     * Statement Foreach
     */

    default void execute(StmtForeach foreach) {
        forEach(foreach.getGenerator().getCollection(), foreach.getGenerator().getVarDecls(), () -> {
            for (Expression filter : foreach.getFilters()) {
                emitter().emit("if (%s) {", expressioneval().evaluate(filter));
                emitter().increaseIndentation();
                memoryStack().enterScope();
            }
            foreach.getBody().forEach(this::execute);
            for (Expression filter : foreach.getFilters()) {
                memoryStack().exitScope();
                emitter().decreaseIndentation();
                emitter().emit("}");
            }
        });
    }

    /*
     * Statement While
     */

    default void execute(StmtWhile stmt) {
        emitter().emit("while (true) {");
        emitter().increaseIndentation();
        memoryStack().enterScope();
        emitter().emit("if (!%s) break;", expressioneval().evaluate(stmt.getCondition()));
        stmt.getBody().forEach(this::execute);
        memoryStack().exitScope();
        emitter().decreaseIndentation();
        emitter().emit("}");
    }

    void forEach(Expression collection, List<GeneratorVarDecl> varDecls, Runnable action);

    default void forEach(ExprBinaryOp binOp, List<GeneratorVarDecl> varDecls, Runnable action) {
        emitter().emit("{");
        emitter().increaseIndentation();
        memoryStack().enterScope();
        if (binOp.getOperations().equals(Collections.singletonList(".."))) {
            Type type = types().declaredType(varDecls.get(0));
            for (VarDecl d : varDecls) {
                emitter().emit("%s;", declarartions().declaration(type, variables().declarationName(d)));
            }
            String temp = variables().generateTemp();
            emitter().emit("%s = %s;", declarartions().declaration(type, temp), expressioneval().evaluate(binOp.getOperands().get(0)));
            emitter().emit("while (%s <= %s) {", temp, expressioneval().evaluate(binOp.getOperands().get(1)));
            emitter().increaseIndentation();
            memoryStack().enterScope();
            for (VarDecl d : varDecls) {
                emitter().emit("%s = %s++;", variables().declarationName(d), temp);
            }
            action.run();
            memoryStack().exitScope();
            emitter().decreaseIndentation();
            emitter().emit("}");
        } else {
            throw new UnsupportedOperationException(binOp.getOperations().get(0));
        }
        memoryStack().exitScope();
        emitter().decreaseIndentation();
        emitter().emit("}");
    }

}
