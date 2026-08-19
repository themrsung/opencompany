package com.coreintra.auth.mail;

import com.coreintra.compat.Texts;
import java.util.Properties;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * SMTP delivery, active only when a host is configured.
 *
 * <p>UTF-8 throughout: subjects and bodies are Korean by default, and a
 * mis-encoded 결재 notification is worse than none because it looks like the
 * system is broken rather than unconfigured.
 */
public class SmtpMailSender implements MailSender {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean startTls;

    public SmtpMailSender(String host, int port, String username, String password, String from,
            boolean startTls) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.startTls = startTls;
    }

    @Override
    public boolean isConfigured() {
        return Texts.hasText(host) && Texts.hasText(from);
    }

    @Override
    public void send(MailMessage message) {
        if (!isConfigured()) {
            throw new MailDeliveryException("SMTP host or from-address is not set", null);
        }
        try {
            Session session = Session.getInstance(properties(), authenticator());
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(from));
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to()));
            mime.setSubject(message.subject(), "UTF-8");
            if (Texts.hasText(message.htmlBody())) {
                mime.setContent(message.htmlBody(), "text/html; charset=UTF-8");
            } else {
                mime.setText(message.plainTextBody(), "UTF-8");
            }
            Transport.send(mime);
        } catch (MessagingException e) {
            // The exception message deliberately omits the body: OTP codes and
            // document contents travel through here.
            throw new MailDeliveryException("SMTP delivery to " + message.to() + " failed", e);
        }
    }

    private Properties properties() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(Texts.hasText(username)));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        props.put("mail.mime.charset", "UTF-8");
        // An on-prem box may sit behind a slow relay; without these the thread
        // can hang indefinitely on a dead server.
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");
        return props;
    }

    private javax.mail.Authenticator authenticator() {
        if (!Texts.hasText(username)) {
            return null;
        }
        return new javax.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        };
    }
}
