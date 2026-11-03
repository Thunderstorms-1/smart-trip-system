package com.smarttrip.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.smarttrip.common.*;
import com.smarttrip.config.JwtAuthInterceptor;
import com.smarttrip.controller.RagController;
import com.smarttrip.entity.User;
import com.smarttrip.mapper.*;
import com.smarttrip.repository.TripRepository;
import com.smarttrip.service.ResourceAccess;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RagControllerTests {
  RagDiagnosticsService diagnostics;
  UserMapper users;
  ResourceAccess access;
  MockMvc mvc;
  @BeforeEach void setup() {
    UserContext.clear();JwtUtil.configure("local-rag-controller-test-secret-at-least-32-bytes");
    users=mock(UserMapper.class);diagnostics=mock(RagDiagnosticsService.class);
    access=new ResourceAccess(mock(TripRepository.class),mock(TripMemberMapper.class));
    var guard=new JwtAuthInterceptor();ReflectionTestUtils.setField(guard,"userMapper",users);
    mvc=MockMvcBuilders.standaloneSetup(new RagController(access,diagnostics))
        .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(guard).build();
    var user=new User();user.setId(7L);user.setStatus(1);user.setTokenVersion(3);
    when(users.findById(7L)).thenReturn(user);
    when(diagnostics.status(anyBoolean())).thenReturn(Map.of("indexStatus","NOT_CHECKED"));
    when(diagnostics.preview(any())).thenReturn(Map.of("previewOnly",true,"chatModelCalled",false));
  }
  @AfterEach void cleanup(){UserContext.clear();}
  String token(int version){return JwtUtil.generateToken(7L,"fixture",version);}
  @Test void bothRoutesRejectAnonymousUsersBeforeDiagnostics() throws Exception {
    mvc.perform(get("/api/rag/status")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/rag/preview").contentType(MediaType.APPLICATION_JSON).content("{\"requirement\":{\"destinations\":[\"苏州\"]}}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(diagnostics);
  }
  @Test void validExistingJwtAllowsStatusAndClearsThreadContext() throws Exception {
    mvc.perform(get("/api/rag/status").header("Authorization","Bearer "+token(3)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.indexStatus").value("NOT_CHECKED"));
    verify(diagnostics).status(false);assertNull(UserContext.getUserId());
  }
  @Test void revokedTokenCannotReadPublicDiagnosticEndpoints() throws Exception {
    mvc.perform(get("/api/rag/status").header("Authorization","Bearer "+token(2)))
        .andExpect(status().isUnauthorized());verifyNoInteractions(diagnostics);
  }
  @Test void authenticatedPreviewUsesTheExistingRequestDto() throws Exception {
    mvc.perform(post("/api/rag/preview").header("Authorization","Bearer "+token(3))
        .contentType(MediaType.APPLICATION_JSON).content("{\"requirement\":{\"destinations\":[\"苏州\"]}}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.chatModelCalled").value(false));
    verify(diagnostics).preview(argThat(r->r.destinations().equals(List.of("苏州"))));
  }
  @Test void directControllerCallsCannotBypassMissingUserContext() {
    assertEquals(401,assertThrows(BusinessException.class,()->new RagController(access,diagnostics).status(false)).getCode());
    verifyNoInteractions(diagnostics);
  }
}
