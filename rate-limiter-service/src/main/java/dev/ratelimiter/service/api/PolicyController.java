package dev.ratelimiter.service.api;

import dev.ratelimiter.core.PolicyProvider;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@Validated
public class PolicyController {

  private final PolicyProvider policyProvider;

  public PolicyController(PolicyProvider policyProvider) {
    this.policyProvider = policyProvider;
  }

  @GetMapping
  public List<PolicyView> list() {
    return policyProvider.policies().stream()
        .sorted(Comparator.comparing(policy -> policy.id()))
        .map(PolicyView::from)
        .toList();
  }

  @GetMapping("/{policyId}")
  public PolicyView get(
      @PathVariable @Size(min = 1, max = 64) @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}") String policyId) {
    return PolicyView.from(policyProvider.requireById(policyId));
  }
}
