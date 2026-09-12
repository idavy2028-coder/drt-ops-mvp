package com.idavy.drtops.domain.onboard;
import com.idavy.drtops.common.ApiResponse;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/terminals/{terminalCode}/video-declarations")
public class VideoDeclarationController {
 private final VideoDeclarationIngressService service;
 public VideoDeclarationController(VideoDeclarationIngressService service){this.service=service;}
 @GetMapping
 public ApiResponse<List<VideoDeclarationIngressService.ObservationView>> read(@PathVariable String terminalCode,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean unresolvedOnly){return ApiResponse.ok(service.read(terminalCode,page,size,unresolvedOnly));}
 @PostMapping("/{observationId}/resolution")
 public ApiResponse<VideoDeclarationIngressService.ObservationView> resolve(@PathVariable String terminalCode,@PathVariable UUID observationId,@RequestBody Resolution request,Authentication authentication){
  UUID actor=authentication.getPrincipal() instanceof UUID id?id:UUID.fromString(authentication.getName());
  return ApiResponse.ok(service.resolve(terminalCode,observationId,request.expectedVersion(),actor,request.reason(),request.evidenceRef()));
 }
 public record Resolution(long expectedVersion,String reason,String evidenceRef){}
}
