package com.robustvision.platform.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TotpTest {
 @Test void rfcVectorsAndReplayWindow(){String secret="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";assertThat(Totp.code(secret,59/30)).isEqualTo("287082");assertThat(Totp.code(secret,1111111109L/30)).isEqualTo("081804");assertThat(Totp.match(secret,"287082",1,-1)).isEqualTo(1);assertThat(Totp.match(secret,"287082",1,1)).isEqualTo(-1);assertThat(Totp.match(secret,"287082",4,-1)).isEqualTo(-1);assertThat(Totp.match(secret,"not-a-code",1,-1)).isEqualTo(-1);}
}
