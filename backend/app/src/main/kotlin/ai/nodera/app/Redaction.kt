package ai.nodera.app

import ai.nodera.domain.identity.SecretRedaction
import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy

/** `%rmsg` in `logback.xml`. Public because Logback instantiates it by name from that file. */
public class RedactingMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = SecretRedaction.redact(event.formattedMessage)
}

/** `%rex`. Separate converter, or the message is clean and the trace beneath it is not. */
public class RedactingThrowableConverter : ThrowableProxyConverter() {
    override fun throwableProxyToString(tp: IThrowableProxy): String =
        SecretRedaction.redact(super.throwableProxyToString(tp))
}
