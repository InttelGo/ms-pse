package com.inttelgo.mspse.config;

import com.inttelgo.mspse.exception.BadRequestException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CurrentActor {

    public Long requireUserId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new BadRequestException("El encabezado X-User-Id es obligatorio");
        }
        String raw = attributes.getRequest().getHeader(RequestHeaders.USER_ID);
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("El encabezado X-User-Id es obligatorio");
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException exception) {
            throw new BadRequestException("X-User-Id debe ser numérico");
        }
    }
}
