package com.photoapp.commons.util;

import com.photoapp.commons.exception.ApplicationException;
import org.springframework.http.HttpStatus;

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
            throw new ApplicationException("Error normalizing DTO", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return dto;
    }

}
