package cn.bigfish.conversation;

import java.io.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeepSeekSmokeTest {
    @Test void noArgumentsOrMissingEnvironmentNeverRunValidation() {
        var bytes=new ByteArrayOutputStream(); var out=new PrintStream(bytes);
        assertThat(DeepSeekSmoke.run(new String[0],false,null,out)).isEqualTo(2);
        assertThat(DeepSeekSmoke.run(new String[]{"--models"},false,null,out)).isEqualTo(3);
        assertThat(bytes.toString()).contains("no requests sent");
    }
    @Test void budgetAndUnknownArgumentsAreRejectedBeforeAnyNetworkCall() {
        var out=new PrintStream(new ByteArrayOutputStream());
        assertThat(DeepSeekSmoke.run(new String[]{"--generate","--model","fixture-model","--requests","4"},false,null,out)).isEqualTo(2);
        assertThat(DeepSeekSmoke.run(new String[]{"--generate","--model","fixture-model","--requests","0"},false,null,out)).isEqualTo(2);
        assertThat(DeepSeekSmoke.run(new String[]{"--api-key","ignored"},false,null,out)).isEqualTo(2);
    }
}
