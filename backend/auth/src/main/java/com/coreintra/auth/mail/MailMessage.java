package com.coreintra.auth.mail;

/** A message to send. Plain text and optional HTML, both carrying the same content. */
public final class MailMessage {

    private final String to;
    private final String subject;
    private final String plainTextBody;
    private final String htmlBody;

    public MailMessage(String to, String subject, String plainTextBody, String htmlBody) {
        this.to = to;
        this.subject = subject;
        this.plainTextBody = plainTextBody;
        this.htmlBody = htmlBody;
    }

    public static MailMessage plainText(String to, String subject, String body) {
        return new MailMessage(to, subject, body, null);
    }

    public String to() {
        return to;
    }

    public String subject() {
        return subject;
    }

    public String plainTextBody() {
        return plainTextBody;
    }

    public String htmlBody() {
        return htmlBody;
    }

    /** Never includes the body: OTP mails go through here and must not reach a log. */
    @Override
    public String toString() {
        return "MailMessage[to=" + to + " subject=" + subject + "]";
    }
}
