package org.example.springboot.util;

import org.apache.ibatis.executor.Executor;

import org.apache.ibatis.mapping.MappedStatement;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.sql.Statement;
import java.util.StringJoiner;

@Component("pageKeyGenerator")
public class PageKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        StringJoiner sj = new StringJoiner(":");
        sj.add(target.getClass().getSimpleName());
        sj.add(method.getName());

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            // 检查参数名是否可用且为需要缓存的参数
            if (param.isNamePresent() && isKeyParameter(param.getName())) {
                Object value = params[i]; // 获取实际参数�?
                sj.add(param.getName() + "=" + (value != null ? value : "null"));
            }
        }
        return sj.toString();
    }

    private boolean isKeyParameter(String paramName) {
        return paramName.equals("currentPage")
                || paramName.equals("size");
    }
}
