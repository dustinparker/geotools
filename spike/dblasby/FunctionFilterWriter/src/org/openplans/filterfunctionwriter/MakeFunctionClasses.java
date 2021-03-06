/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2005-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.openplans.filterfunctionwriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

/**
 * Basic idea:
 * 
 * 1. for each method in the StaticGeometry class (or whatever class you specify -
 * see main() ) 2. make a .java file * put the header in (ie. includes, etc...) *
 * put the actual code in (see "emit()" below) * put the footer in (ie. finish
 * the class "}")
 * 
 * @author dblasby
 */
public class MakeFunctionClasses {
    private static final String LICENSE = "/*\n"
            + " *    GeoTools - The Open Source Java GIS Toolkit\n"
            + " *    http://geotools.org\n"
            + " *\n"
            + " *    (C) 2005-2008, Open Source Geospatial Foundation (OSGeo)\n"
            + " *    \n"
            + " *    This library is free software; you can redistribute it and/or\n"
            + " *    modify it under the terms of the GNU Lesser General Public\n"
            + " *    License as published by the Free Software Foundation;\n"
            + " *    version 2.1 of the License.\n"
            + " *\n"
            + " *    This library is distributed in the hope that it will be useful,\n"
            + " *    but WITHOUT ANY WARRANTY; without even the implied warranty of\n"
            + " *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU\n"
            + " *    Lesser General Public License for more details.\n" + " */";

    public static void main(String[] args) {
        MakeFunctionClasses cg = new MakeFunctionClasses();

        cg.handleClass(org.geotools.filter.function.StaticGeometry.class); // parent
        // of
        // all
        // geometry
        // types
    }

    public void handleClass(Class c) {
        Method[] methods = c.getDeclaredMethods();
        for (int t = 0; t < methods.length; t++) {
            try {
                Method method = methods[t];
                File f = new File(
                        "src/org/geotools/filter/function/FilterFunction_"
                                + method.getName() + ".java");
                PrintStream ps = new PrintStream(new FileOutputStream(f));

                emitHeader(method, ps);
                emitCode(method, ps);
                emitFooter(method, ps);

                ps.close();
                PrintStream printStream = System.out;
                writeServiceInfo(method, printStream, method.getName());

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void writeServiceInfo(Method m, PrintStream printstream, String name) {
        printstream.println("org.geotools.filter.function.FilterFunction_"
                + name);
    }

    public void emitHeader(Method m, PrintStream printstream) {
        printstream.println("package org.geotools.filter.function;");
        printstream.println(LICENSE);
        printstream.println("");
        printstream.println("");
        printstream
                .println("//this code is autogenerated - you shouldnt be modifying it!");
        printstream.println("");
        printstream
                .println("import com.vividsolutions.jts.geom.*; //generic JTS support");
        printstream
                .println("import org.geotools.filter.function.StaticGeometry; //make sure you include this or you will not be able to call the functions!!");
        printstream.println("");
        printstream.println("");

        printstream.println("import org.geotools.feature.Feature;");
        printstream.println("import org.geotools.filter.Expression;");
        printstream.println("import org.geotools.filter.FilterFactory;");
        printstream.println("import org.geotools.filter.FunctionExpression;");
        printstream
                .println("import org.geotools.filter.FunctionExpressionImpl;");
        printstream.println("import org.geotools.filter.LiteralExpression;");
        printstream.println("");
        printstream.println("");
        printstream
                .println("public class "
                        + "FilterFunction_"
                        + m.getName()
                        + " extends FunctionExpressionImpl implements FunctionExpression ");
        printstream.println("{");
        printstream.println("");
        printstream.println("");
        printstream.println("");
        printstream.println("");
        printstream.println("public FilterFunction_" + m.getName() + "(){");
        printstream.println("        super(\"" + m.getName() + "\");");
        printstream.println("}");
        printstream.println("");
        printstream.println("");
        printstream.println("public int getArgCount()");
        printstream.println("{");
        printstream.println("      return " + m.getParameterTypes().length
                + ";");
        printstream.println("}");
        printstream.println("");
        printstream.println("");
        printstream.println("");
    }

    public void emitFooter(Method m, PrintStream printstream) {
        printstream.println("}");
        printstream.println("");
    }

    public void emitCode(Method m, PrintStream printstream) {
        printstream.println("public Object evaluate(Feature feature){");

        // variable decs
        for (int t = 0; t < m.getParameterTypes().length; t++) {
            printstream.println("      "
                    + formatClassName(m.getParameterTypes()[t]) + "  arg" + t
                    + ";");
        }

        printstream.println("");
        printstream.println("");
        // assignments
        for (int t = 0; t < m.getParameterTypes().length; t++) {
            printstream
                    .println("      try{  //attempt to get value and perform conversion");
            printstream.print("                arg" + t + " = ");
            if (isNumber(m.getParameterTypes()[t])) {
                if ((m.getParameterTypes()[t] == int.class)) {
                    printstream.println("((Number) getExpression(" + t
                            + ").evaluate(feature)).intValue();");
                } else if ((m.getParameterTypes()[t] == double.class)) {
                    printstream.println("((Number) getExpression(" + t
                            + ").evaluate(feature)).doubleValue();");
                } else
                    throw new IllegalArgumentException(
                            "dont know how to handle this - "
                                    + m.getParameterTypes()[t]);

            } else if ((m.getParameterTypes()[t] == boolean.class)) {
                printstream.println("((Boolean) getExpression(" + t
                        + ").evaluate(feature)).booleanValue();");
            } else if ((m.getParameterTypes()[t] == String.class)) {
                printstream
                        .println("(getExpression("
                                + t
                                + ").evaluate(feature)).toString(); // extra protection for strings");
            } else // class
            {
                printstream.println("("
                        + formatClassName(m.getParameterTypes()[t])
                        + ") getExpression(" + t + ").evaluate(feature);");
            }
            printstream.println("      }");
            printstream
                    .println("      catch (Exception e) // probably a type error");
            printstream.println("      {");
            printstream
                    .println("            throw new IllegalArgumentException(\"Filter Function problem for function "
                            + m.getName()
                            + " argument #"
                            + t
                            + " - expected type "
                            + formatClassName(m.getParameterTypes()[t])
                            + "\");");
            printstream.println("      }");
            printstream.println("");
        }

        // perform computation

        if (isNumber(m.getReturnType())) {
            if (m.getReturnType() == int.class)
                printstream.print("      return new Integer(StaticGeometry."
                        + m.getName() + "(");
            if (m.getReturnType() == double.class)
                printstream.print("      return new Double(StaticGeometry."
                        + m.getName() + "(");
        } else if (m.getReturnType() == boolean.class) {
            printstream.print("      return new Boolean(StaticGeometry."
                    + m.getName() + "(");
        } else // class
        {
            printstream.print("      return (StaticGeometry." + m.getName()
                    + "(");
        }

        for (int t = 0; t < m.getParameterTypes().length; t++) {
            if (t != 0)
                printstream.print(",");
            printstream.print("arg" + (t));
        }
        printstream.println(" ));");

        printstream.println("}");

    }

    /**
     * @param class1
     * @return
     */
    private boolean isNumber(Class class1) {
        if ((class1 == int.class) || (class1 == double.class)) {
            return true;
        }
        return false;
    }

    public String formatClassName(Class c) {
        String fullName = c.getName();
        int indx = fullName.lastIndexOf('.');
        if (indx == -1)
            return fullName;
        else
            return fullName.substring(indx + 1);

    }

}
