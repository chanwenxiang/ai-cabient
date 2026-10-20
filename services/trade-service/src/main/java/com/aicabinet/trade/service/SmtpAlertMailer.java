package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * 运营告警 SMTP 邮件发送（零依赖手写客户端）。
 *
 * <p><b>为什么不引 jakarta.mail</b>：全仓构建走 {@code mvn -o} 离线仓库，mail 依赖不在缓存，
 * 引入即卡构建。SMTP 会话本身只是几条文本命令（EHLO/AUTH LOGIN/MAIL/RCPT/DATA），
 * 手写约百行且无状态，比为此新增一个依赖树更可控。</p>
 *
 * <p>安全口径：仅支持 SSL 直连（465 口径，阿里企业邮箱）；授权码来自运营台
 * {@code ops.alert.email.password}（三方客户端安全密码，非登录密码）；发件失败由调用方
 * 记日志并计入「渠道未送达」，不影响告警主流程。</p>
 */
@Service
public class SmtpAlertMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpAlertMailer.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /**
     * 发送一封纯文本告警邮件。
     *
     * @return 投递成功（服务器 2xx 收尾）返回 true；任何一步失败抛出 IllegalStateException（调用方记日志）
     */
    public void send(String host, int port, String username, String password,
                     List<String> recipients, String subject, String body) {
        if (host == null || host.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank()
                || recipients == null || recipients.isEmpty()) {
            throw new IllegalStateException("SMTP alert mailer not configured (host/username/password/recipients)");
        }
        try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.startHandshake();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));

            expect(in, "220", "greeting");
            command(out, "EHLO aicabinet");
            readMultiline(in, "250", "EHLO");
            // 阿里企业邮箱要求 AUTH LOGIN（授权码/安全密码，非登录密码）
            command(out, "AUTH LOGIN");
            expect(in, "334", "AUTH LOGIN prompt");
            command(out, Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)));
            expect(in, "334", "AUTH username prompt");
            command(out, Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            expect(in, "235", "AUTH result");
            command(out, "MAIL FROM:<" + username + ">");
            expect(in, "250", "MAIL FROM");
            for (String to : recipients) {
                command(out, "RCPT TO:<" + to.trim() + ">");
                expect(in, "250", "RCPT TO " + to);
            }
            command(out, "DATA");
            expect(in, "354", "DATA");
            out.write(buildMimeMessage(username, recipients, subject, body));
            out.write("\r\n.\r\n");
            out.flush();
            expect(in, "250", "message accepted");
            command(out, "QUIT");
            socket.close();
            log.info("smtp alert mail sent to={} subject={}", recipients, subject);
        } catch (Exception e) {
            throw new IllegalStateException("SMTP send failed via " + host + ":" + port, e);
        }
    }

    /** MIME 报文（包私有便于单测）：主题与正文均 UTF-8 base64，规避 8bit/点 stuffing 问题。 */
    String buildMimeMessage(String from, List<String> recipients, String subject, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(from).append("\r\n");
        sb.append("To: ").append(String.join(", ", recipients)).append("\r\n");
        sb.append("Subject: =?UTF-8?B?").append(
                Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))).append("?=\r\n");
        sb.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n");
        sb.append("\r\n");
        String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < encoded.length(); i += 76) {
            sb.append(encoded, i, Math.min(encoded.length(), i + 76)).append("\r\n");
        }
        return sb.toString();
    }

    private void command(BufferedWriter out, String line) throws java.io.IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
    }

    /** 读一个应答；多行应答（形如 250-…）一直读到 250（空格结尾）为止。 */
    private void readMultiline(BufferedReader in, String code, String step) throws java.io.IOException {
        while (true) {
            String line = in.readLine();
            if (line == null) {
                throw new IllegalStateException(step + ": connection closed");
            }
            if (line.startsWith(code + " ")) {
                return;
            }
            if (!line.startsWith(code + "-")) {
                throw new IllegalStateException(step + ": expected " + code + " got: " + line);
            }
        }
    }

    private void expect(BufferedReader in, String code, String step) throws java.io.IOException {
        String line = in.readLine();
        if (line == null) {
            throw new IllegalStateException(step + ": connection closed");
        }
        if (!line.startsWith(code)) {
            throw new IllegalStateException(step + ": expected " + code + " got: " + line);
        }
    }
}
