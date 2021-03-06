package org.xdi.model.custom.script.type.uma;

import java.util.List;

import org.xdi.model.custom.script.type.BaseExternalType;
import org.xdi.model.uma.ClaimDefinition;

/**
 * @author yuriyz on 05/30/2017.
 */
public interface UmaRptPolicyType extends BaseExternalType {

    List<ClaimDefinition> getRequiredClaims(Object authorizationContext);

    boolean authorize(Object authorizationContext);

    String getClaimsGatheringScriptName(Object authorizationContext);
}
