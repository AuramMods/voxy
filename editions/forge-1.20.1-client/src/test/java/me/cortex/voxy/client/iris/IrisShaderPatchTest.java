package me.cortex.voxy.client.iris;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class IrisShaderPatchTest {
    private static final String LEGACY_TAA_HOOK = "{ return offsets[framemod8] * texelSize * float(auramVoxyTaaEnabled); }";

    @Test
    void pairsLegacyHookWithNewerManifest() {
        String[] uniforms = {"texelSize", "framemod8"};
        assertArrayEquals(new String[]{"texelSize", "framemod8", "auramVoxyTaaEnabled"},
                IrisShaderPatch.includeLegacyAuramTaaUniform(uniforms, LEGACY_TAA_HOOK));
    }

    @Test
    void keepsExistingLegacyUniform() {
        String[] uniforms = {"texelSize", "framemod8", "auramVoxyTaaEnabled"};
        assertSame(uniforms, IrisShaderPatch.includeLegacyAuramTaaUniform(uniforms, LEGACY_TAA_HOOK));
    }

    @Test
    void leavesOtherTaaHooksAlone() {
        String[] uniforms = {"texelSize", "framemod8"};
        assertSame(uniforms, IrisShaderPatch.includeLegacyAuramTaaUniform(uniforms, null));
        assertSame(uniforms, IrisShaderPatch.includeLegacyAuramTaaUniform(uniforms,
                "{ return offsets[framemod8] * texelSize; }"));
        assertSame(uniforms, IrisShaderPatch.includeLegacyAuramTaaUniform(uniforms,
                "{ return offsets[framemod8] * texelSize * float(auramVoxyTaaEnabledOther); }"));
    }
}
