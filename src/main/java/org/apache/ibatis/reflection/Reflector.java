/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

    /**
     * 反射对应的类
     */
    private final Class<?> type;
    /**
     * 可读属性数组
     */
    private final String[] readablePropertyNames;
    /**
     * 可写属性数组
     */
    private final String[] writeablePropertyNames;
    /**
     * 属性的setter方法， key为属性名称，value为属性的反射调用方法。
     */
    private final Map<String, Invoker> setMethods = new HashMap<>();
    /**
     * 属性的getter方法， key为属性名称，value为属性的反射调用方法。
     */
    private final Map<String, Invoker> getMethods = new HashMap<>();
    /**
     * 属性的setter方法的属性的类型，一般就是属性的类型
     */
    private final Map<String, Class<?>> setTypes = new HashMap<>();
    /**
     * 属性的getter方法的属性的类型，一般就是属性的类型
     */
    private final Map<String, Class<?>> getTypes = new HashMap<>();
    /**
     * 默认的构造方法
     */
    private Constructor<?> defaultConstructor;
    /**
     * 不区分大小写的属性集合
     */
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

    /**
     * 构造方法，传入反射的类型
     *
     * @param clazz 反射处理的类
     */
    public Reflector(Class<?> clazz) {
        // 设置对应的类
        type = clazz;
        // 初始化对应类的默认构造方法
        addDefaultConstructor(clazz);
        // 通过遍历 getter 方法，初始化 getMethods map 和 getTypes map
        addGetMethods(clazz);
        // 通过遍历 setter 方法，初始化 setMethods map 和 setTypes map
        addSetMethods(clazz);
        // 通过遍历 fields 属性，初始化 getMethods getTypes setMethods setTypes， setFields 是对setGetMethods 和 setSetMethods的补充，因为有些变量没有getter/setter方法
        addFields(clazz);
        // 以下为初始化 readablePropertyNames， writeablePropertyNames， caseInsensitivePropertyMap 属性。
        readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
        writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writeablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    /**
     * 初始化对应类的默认构造方法
     *
     * @param clazz
     */
    private void addDefaultConstructor(Class<?> clazz) {
        // 获取所有的构造方法，包括私有
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        // 每个类都有默认的构造方法，所以不需要判断集合是否为空
        for (Constructor<?> constructor : constructors) {
            // 构造方法的属性为空的就是默认的构造方法
            if (constructor.getParameterTypes().length == 0) {
                this.defaultConstructor = constructor;
            }
        }
    }

    /**
     * 初始化类的属性的 getter 方法
     *
     * @param cls
     */
    private void addGetMethods(Class<?> cls) {
        // 属性与其getter方法的映射，因为可能存在桥接方法，所以value是list
        Map<String, List<Method>> conflictingGetters = new HashMap<>();
        // 获取所有的方法
        Method[] methods = getClassMethods(cls);
        for (Method method : methods) {
            // 有入参，肯定不是类的 getter 方法
            if (method.getParameterTypes().length > 0) {
                continue;
            }
            String name = method.getName();
            // 判断是get 和 is 开头的方法， 不建议使用is 开头的方法。
            if ((name.startsWith("get") && name.length() > 3) || (name.startsWith("is") && name.length() > 2)) {
                // 获得属性名，纯字符串操作，没啥技术含量
                name = PropertyNamer.methodToProperty(name);
                // 添加到 conflictingGetters 中
                addMethodConflict(conflictingGetters, name, method);
            }
        }
        // 解决 getting 冲突方法，最终，一个属性，只保留一个对应的方法。
        resolveGetterConflicts(conflictingGetters);
    }

    /**
     * 解决 getting 冲突方法，最终，一个属性，只保留一个对应的方法。
     *
     * @param conflictingGetters
     */
    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            // 保留的方法
            Method winner = null;
            String propName = entry.getKey();
            // 循环遍历某个属性的所有 getter 方法
            for (Method candidate : entry.getValue()) {
                if (winner == null) {
                    // 为空的时候，还要啥自行车啊，直接往后遍历。
                    // candidate 候选者
                    winner = candidate;
                    continue;
                }
                // 获取两个方法的返回值
                Class<?> winnerType = winner.getReturnType();
                Class<?> candidateType = candidate.getReturnType();
                if (candidateType.equals(winnerType)) {
                    if (!boolean.class.equals(candidateType)) {
                        // 如果两个方法的返回值一样，且不是boolean 方法 那是要抛异常的
                        // 应该在 getClassMethods 方法中，已经合并。所以抛出 ReflectionException 异常
                        throw new ReflectionException(
                                "Illegal overloaded getter method with ambiguous type for property "
                                        + propName + " in class " + winner.getDeclaringClass()
                                        + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                    } else if (candidate.getName().startsWith("is")) {
                        // 如果返回值一样，但是是Boolean类型，那就用is方法替换getter方法，所以不建议is方法，太麻烦。
                        winner = candidate;
                    }
                } else if (candidateType.isAssignableFrom(winnerType)) {
                    // OK getter type is descendant
                } else if (winnerType.isAssignableFrom(candidateType)) {
                    // winnerType 是 candidateType 的父类，要用子类
                    // 说明 winner 的返回值 是 candidate 返回值得父类，所以要替换成子类的。
                    winner = candidate;
                } else {
                    // 如果都不符合，说明俩方法的返回值根本没关系啊，就要抛异常啦。
                    throw new ReflectionException(
                            "Illegal overloaded getter method with ambiguous type for property "
                                    + propName + " in class " + winner.getDeclaringClass()
                                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                }
            }
            addGetMethod(propName, winner);
        }
    }

    private void addGetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            getMethods.put(name, new MethodInvoker(method));
            Type returnType = TypeParameterResolver.resolveReturnType(method, type);
            getTypes.put(name, typeToClass(returnType));
        }
    }

    /**
     * 通过遍历 setter 方法，初始化 setMethods map 和 setTypes map
     *
     * @param cls
     */
    private void addSetMethods(Class<?> cls) {
        Map<String, List<Method>> conflictingSetters = new HashMap<>();
        Method[] methods = getClassMethods(cls);
        for (Method method : methods) {
            String name = method.getName();
            if (name.startsWith("set") && name.length() > 3) {
                if (method.getParameterTypes().length == 1) {
                    name = PropertyNamer.methodToProperty(name);
                    addMethodConflict(conflictingSetters, name, method);
                }
            }
        }
        // 和addGetMethods的方法不同的地方
        resolveSetterConflicts(conflictingSetters);
    }

    /**
     * 把属性和它的方法放入 Map 中
     *
     * @param conflictingMethods 属性和它所有方法的集合
     * @param name               属性
     * @param method             方法
     */
    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        // 总而言之就是
        // 通过name 在 conflictingMethods 中获取值
        // 有值得话返回list，并把method 放进去
        // 没有值得话，新建一个list，并把method 放进去
        List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
        list.add(method);
    }

    /**
     * 处理setter 方法冲突
     *
     * @param conflictingSetters
     */
    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        for (String propName : conflictingSetters.keySet()) {
            // 获取属性的所有setter方法
            List<Method> setters = conflictingSetters.get(propName);
            Class<?> getterType = getTypes.get(propName);
            Method match = null;
            ReflectionException exception = null;
            // 遍历所有的setter方法，包括父类和接口的
            for (Method setter : setters) {
                Class<?> paramType = setter.getParameterTypes()[0];
                if (paramType.equals(getterType)) {
                    // 参数和返回值是同一类型，应该就是该setter方法
                    match = setter;
                    break;
                }
                if (exception == null) {
                    try {
                        match = pickBetterSetter(match, setter, propName);
                    } catch (ReflectionException e) {
                        // 如果抛出异常，就是没找到呗
                        match = null;
                        exception = e;
                    }
                }
            }
            if (match == null) {
                // 没找到抛异常
                throw exception;
            } else {
                // 找到了设置 setMethods 和 setTypes 方法
                addSetMethod(propName, match);
            }
        }
    }

    /**
     * 选择出一个更合适的 Method
     * 其实就是看那个方法的返回值是子类就返回那个
     * 如果两个方法的返回值没有关系，就抛出异常。
     *
     * @param setter1  方法1
     * @param setter2  方法2
     * @param property 属性
     * @return
     */
    private Method pickBetterSetter(Method setter1, Method setter2, String property) {
        if (setter1 == null) {
            return setter2;
        }
        Class<?> paramType1 = setter1.getParameterTypes()[0];
        Class<?> paramType2 = setter2.getParameterTypes()[0];
        // 选择返回值是子类的那个
        if (paramType1.isAssignableFrom(paramType2)) {
            return setter2;
        } else if (paramType2.isAssignableFrom(paramType1)) {
            return setter1;
        }
        throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
                + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
                + paramType2.getName() + "'.");
    }

    /**
     * 设置setMethods 和 setTypes 方法
     *
     * @param name
     * @param method
     */
    private void addSetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            setMethods.put(name, new MethodInvoker(method));
            Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
            setTypes.put(name, typeToClass(paramTypes[0]));
        }
    }

    /**
     * 获取java.lang.reflect.Type 对应的类
     *
     * @param src
     * @return
     */
    private Class<?> typeToClass(Type src) {
        Class<?> result = null;
        if (src instanceof Class) {
            // 普通类型，直接返回
            result = (Class<?>) src;
        } else if (src instanceof ParameterizedType) {
            // 泛型类型，使用泛型
            result = (Class<?>) ((ParameterizedType) src).getRawType();
        } else if (src instanceof GenericArrayType) {
            // 泛型数组，获得具体的类
            Type componentType = ((GenericArrayType) src).getGenericComponentType();
            if (componentType instanceof Class) {
                // 普通类型
                result = Array.newInstance((Class<?>) componentType, 0).getClass();
            } else {
                // 递归执行
                Class<?> componentClass = typeToClass(componentType);
                result = Array.newInstance(componentClass, 0).getClass();
            }
        }
        if (result == null) {
            // 都不符合返回Object类
            result = Object.class;
        }
        return result;
    }

    private void addFields(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (!setMethods.containsKey(field.getName())) {
                // issue #379 - removed the check for final because JDK 1.5 allows
                // modification of final fields through reflection (JSR-133). (JGB)
                // pr #16 - final static can only be set by the classloader
                // 获取 field 的修饰符，如果不是final 和 static 修饰的，才放入setMethods 和 setTypes
                int modifiers = field.getModifiers();
                if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                    addSetField(field);
                }
            }
            if (!getMethods.containsKey(field.getName())) {
                addGetField(field);
            }
        }
        // 递归遍历啦
        if (clazz.getSuperclass() != null) {
            addFields(clazz.getSuperclass());
        }
    }

    private void addSetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            setTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    /**
     * 保存getMethods 和 getTypes
     *
     * @param field
     */
    private void addGetField(Field field) {
        // 校验是否是正常属性
        if (isValidPropertyName(field.getName())) {
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            // 获取字段的类型
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            getTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    /**
     * 校验是否是正常的变量
     *
     * @param name
     * @return
     */
    private boolean isValidPropertyName(String name) {
        return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
    }

    /**
     * This method returns an array containing all methods
     * declared in this class and any superclass.
     * We use this method, instead of the simpler <code>Class.getMethods()</code>,
     * because we want to look for private methods as well.
     *
     * @param cls The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> cls) {
        // key 方法签名，并不是属性， value 方法
        Map<String, Method> uniqueMethods = new HashMap<>();
        // 用来记录当前类
        Class<?> currentClass = cls;
        // 循环处理类，父类，父类，直到 Object 类
        while (currentClass != null && currentClass != Object.class) {
            // getMethod 返回某个类的所有公用（public）方法包括其继承类的公用方法
            // getDeclaredMethods 包括公共、保护、默认（包）访问和私有方法，但不包括继承的方法。
            addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());
            // we also need to look for interface methods because the class may be abstract
            // 需要拿到接口的方法，因为可能反射的是抽象类
            Class<?>[] interfaces = currentClass.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                addUniqueMethods(uniqueMethods, anInterface.getMethods());
            }
            // 处理完指向父类，然后循环
            currentClass = currentClass.getSuperclass();
        }

        // 拿到所有的唯一方法并返回
        Collection<Method> methods = uniqueMethods.values();
        return methods.toArray(new Method[methods.size()]);
    }

    /**
     * 获取某个方法签名的唯一方法放入 uniqueMethods 中
     *
     * @param uniqueMethods
     * @param methods
     */
    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
        for (Method currentMethod : methods) {
            // 判断当前方法是否是桥接方法，桥接方法是jvm为了兼容泛型而自动生成的方法，它是把泛型编译的Object类型，转成泛型的具体类型的方法。
            // 泛型为了兼容，会把泛型转成 Object ，然后在具体的类里，增加一个 Object 参数的方法，转成具体的类型，这个方法就是 bridge 的
            if (!currentMethod.isBridge()) {
                String signature = getSignature(currentMethod);
                // 如果有了那一定是重写了，但是遍历方法是从当前类往父类遍历的，所以不用替换。
                if (!uniqueMethods.containsKey(signature)) {
                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

    /**
     * 获取方法签名
     * 格式：returnType#方法名:参数名1,参数名2,参数名3 。
     * 举例：void#checkPackageAccess:java.lang.ClassLoader,boolean
     *
     * @param method 方法
     * @return String 方法签名
     */
    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }
        sb.append(method.getName());
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i == 0) {
                sb.append(':');
            } else {
                sb.append(',');
            }
            sb.append(parameters[i].getName());
        }
        return sb.toString();
    }

    /**
     * Checks whether can control member accessible.
     *
     * @return If can control member accessible, it return {@literal true}
     * @since 3.5.0
     */
    public static boolean canControlMemberAccessible() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets the name of the class the instance provides information for.
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    public Invoker getSetInvoker(String propertyName) {
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /**
     * Gets the type for a property setter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets the type for a property getter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets an array of the readable properties for an object.
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /**
     * Gets an array of the writable properties for an object.
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writeablePropertyNames;
    }

    /**
     * Check to see if a class has a writable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.keySet().contains(propertyName);
    }

    /**
     * Check to see if a class has a readable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.keySet().contains(propertyName);
    }

    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}
