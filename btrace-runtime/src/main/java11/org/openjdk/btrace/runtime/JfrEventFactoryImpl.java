package org.openjdk.btrace.runtime;

import jdk.jfr.AnnotationElement;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventFactory;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.Registered;
import jdk.jfr.StackTrace;
import jdk.jfr.ValueDescriptor;
import org.openjdk.btrace.core.DebugSupport;
import org.openjdk.btrace.core.jfr.JfrEvent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

final class JfrEventFactoryImpl implements JfrEvent.Factory {
    private static final Pattern TYPE_NAME_SPLIT = Pattern.compile("\\s+");
    private static final Map<String, Class<?>> VALUE_TYPES;

    static {
        VALUE_TYPES = new HashMap<>();
        VALUE_TYPES.put("byte", byte.class);
        VALUE_TYPES.put("boolean", boolean.class);
        VALUE_TYPES.put("char", char.class);
        VALUE_TYPES.put("int", int.class);
        VALUE_TYPES.put("short", short.class);
        VALUE_TYPES.put("float", float.class);
        VALUE_TYPES.put("long", long.class);
        VALUE_TYPES.put("double", double.class);
        VALUE_TYPES.put("string", String.class);
    }

    private final EventFactory eventFactory;
    private final Map<String, Integer> fieldIndex = new HashMap<>();

    private Runnable periodicHook = null;

    JfrEventFactoryImpl(JfrEvent.Template template) {
        List<AnnotationElement> defAnnotations = new ArrayList<>();
        List<ValueDescriptor> defFields = new ArrayList<>();
        defAnnotations.add(new AnnotationElement(Name.class, template.getName()));
        defAnnotations.add(new AnnotationElement(Registered.class, true));
        defAnnotations.add(new AnnotationElement(StackTrace.class, template.isStacktrace()));
        if (template.getLabel() != null) {
            defAnnotations.add(new AnnotationElement(Label.class, template.getLabel()));
        }
        if (template.getDescription() != null) {
            defAnnotations.add(new AnnotationElement(Description.class, template.getDescription()));
        }
        if (template.getCategory() != null) {
            defAnnotations.add(new AnnotationElement(Category.class, template.getCategory()));
        }
        if (template.getPeriod() != null) {
            defAnnotations.add(new AnnotationElement(Period.class, template.getPeriod()));
        }
        int counter = 0;
        StringTokenizer tokenizer = new StringTokenizer(template.getFields(), ",");
        while (tokenizer.hasMoreTokens()) {
            String nextToken = tokenizer.nextToken().trim();
            String[] typeName = TYPE_NAME_SPLIT.split(nextToken);
            defFields.add(new ValueDescriptor(VALUE_TYPES.get(typeName[0].toLowerCase()), typeName[1]));
            fieldIndex.put(typeName[1], counter++);
        }
        eventFactory = EventFactory.create(defAnnotations, defFields);
        eventFactory.register();
        if (template.getPeriod() != null && template.getPeriodicHandler() != null) {
            addJfrPeriodicEvent(template);
        }
    }

    @Override
    public JfrEvent newEvent() {
        return new JfrEventImpl(eventFactory.newEvent(), fieldIndex);
    }

    private void addJfrPeriodicEvent(JfrEvent.Template template) {
        try {
            Class<?> handlerClass = Class.forName(template.getOwner());
            Method handlerMethod = handlerClass.getMethod(template.getPeriodicHandler(), JfrEvent.class);
            Runnable hook = (Runnable) Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class[]{Runnable.class}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if (method.getName().equals("run")) {
                        try {
                            JfrEvent event = newEvent();
                            return handlerMethod.invoke(null, event);
                        } catch (Throwable t) {
                            t.printStackTrace(System.out);
                            throw t;
                        }
                    } else {
                        return method.invoke(this, args);
                    }
                }
            });
            Class<? extends Event> eClz = eventFactory.newEvent().getClass();
            FlightRecorder.addPeriodicEvent(eClz, hook);
            periodicHook = hook;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            StringBuilder msg = new StringBuilder("Unable to register periodic JFR event of type '");
            String eMsg = e.getMessage();
            msg.append(eMsg.replace('/', '.'));
            msg.append("'");
            DebugSupport.info(msg.toString());
        } catch (Throwable ignored) {
        }
    }

    void unregister() {
        if (periodicHook != null) {
            FlightRecorder.removePeriodicEvent(periodicHook);
        }
        eventFactory.unregister();

    }
}
