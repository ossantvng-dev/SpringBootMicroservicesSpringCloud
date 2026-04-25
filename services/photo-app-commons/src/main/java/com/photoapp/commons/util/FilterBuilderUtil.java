package com.photoapp.commons.util;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

public class FilterBuilderUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static <T> T mapToFilter(Map<String, String> params, Class<T> clazz) {
        return objectMapper.convertValue(params, clazz);
    }

}
