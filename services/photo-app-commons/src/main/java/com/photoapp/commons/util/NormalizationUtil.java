package com.photoapp.commons.util;

import java.lang.reflect.Field;

public class NormalizationUtil {

    public static <T> T normalizeInputDTO(T dto) {
        if (dto == null) {
            return null;
        }
        try {
            for (Field field : dto.getClass().getDeclaredFields()) {
                if (field.getType().equals(String.class)) {
                    field.setAccessible(true);
                    String value = (String) field.get(dto);
                    if (value != null) {
                        field.set(dto, value.trim());
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error normalizing DTO", e);
        }
        return dto;
    }

}
