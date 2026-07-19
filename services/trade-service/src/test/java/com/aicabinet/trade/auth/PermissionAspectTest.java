package com.aicabinet.trade.auth;

import com.aicabinet.trade.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionAspectTest {

    private static final long USER_ID = 100000001L;

    @Mock
    private PermissionService permissionService;

    @Mock
    private HttpServletRequest request;

    private PermissionAspect aspect;
    private MockedStatic<RequestContextHolder> requestContextHolder;

    @BeforeEach
    void setUp() {
        aspect = new PermissionAspect(permissionService);
        requestContextHolder = mockStatic(RequestContextHolder.class);
        ServletRequestAttributes attrs = new ServletRequestAttributes(request);
        requestContextHolder.when(RequestContextHolder::getRequestAttributes).thenReturn(attrs);
    }

    @AfterEach
    void tearDown() {
        requestContextHolder.close();
    }

    @Test
    void missingUser_unauthorized() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(null);
        RequiresPermissions ann = mockAnnotation(new String[]{"ops:order:export"}, RequiresPermissions.Logical.AND);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> aspect.checkMethod(ann));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void exportPermGranted_passes() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(USER_ID);
        RequiresPermissions ann = mockAnnotation(new String[]{"ops:order:export"}, RequiresPermissions.Logical.AND);
        doNothing().when(permissionService).requirePermission(USER_ID, "ops:order:export");

        aspect.checkMethod(ann);

        verify(permissionService).requirePermission(USER_ID, "ops:order:export");
    }

    @Test
    void exportPermDenied_forbidden() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(USER_ID);
        RequiresPermissions ann = mockAnnotation(new String[]{"ops:session:export"}, RequiresPermissions.Logical.AND);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(permissionService).requirePermission(USER_ID, "ops:session:export");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> aspect.checkMethod(ann));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void orLogic_anyMatch_passes() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(USER_ID);
        RequiresPermissions ann = mockAnnotation(
                new String[]{"ops:sku:edit", "ops:sku:import"},
                RequiresPermissions.Logical.OR);
        doNothing().when(permissionService).requireAnyPermission(USER_ID, "ops:sku:edit", "ops:sku:import");

        aspect.checkMethod(ann);

        verify(permissionService).requireAnyPermission(USER_ID, "ops:sku:edit", "ops:sku:import");
    }

    @Test
    void andLogic_requiresEveryCode() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(USER_ID);
        RequiresPermissions ann = mockAnnotation(
                new String[]{"ops:sku:edit", "ops:vision:edit"},
                RequiresPermissions.Logical.AND);
        doNothing().when(permissionService).requirePermission(USER_ID, "ops:sku:edit");
        doNothing().when(permissionService).requirePermission(USER_ID, "ops:vision:edit");

        aspect.checkMethod(ann);

        verify(permissionService).requirePermission(USER_ID, "ops:sku:edit");
        verify(permissionService).requirePermission(USER_ID, "ops:vision:edit");
    }

    @Test
    void andLogic_secondDenied_forbidden() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(USER_ID);
        RequiresPermissions ann = mockAnnotation(
                new String[]{"ops:sku:edit", "ops:vision:edit"},
                RequiresPermissions.Logical.AND);
        doNothing().when(permissionService).requirePermission(USER_ID, "ops:sku:edit");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(permissionService).requirePermission(USER_ID, "ops:vision:edit");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> aspect.checkMethod(ann));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void emptyValue_skipsCheck() {
        when(request.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(USER_ID);
        RequiresPermissions ann = mockAnnotation(new String[]{}, RequiresPermissions.Logical.AND);

        aspect.checkMethod(ann);

        verify(permissionService, never()).requirePermission(anyLong(), anyString());
        verify(permissionService, never()).requireAnyPermission(anyLong(), any());
    }

    private static RequiresPermissions mockAnnotation(String[] value, RequiresPermissions.Logical logical) {
        RequiresPermissions ann = mock(RequiresPermissions.class);
        lenient().when(ann.value()).thenReturn(value);
        lenient().when(ann.logical()).thenReturn(logical);
        return ann;
    }
}
