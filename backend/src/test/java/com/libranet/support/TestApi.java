package com.libranet.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Small fixture helpers shared by the suites. */
public final class TestApi {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TestApi() {}

    public static String login(MockMvc mvc, String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.path("accessToken").asText();
    }

    public static JsonNode register(MockMvc mvc, String email, String password, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                                + "\",\"fullName\":\"" + name + "\"}"))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static JsonNode readJson(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** A small valid PNG (two-tone) for media upload tests — guaranteed decodable. */
    public static byte[] pngBytes(int w, int h) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x3366AA));
        g.fillRect(0, 0, w, h);
        g.setColor(new java.awt.Color(0xE8B53A));
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
