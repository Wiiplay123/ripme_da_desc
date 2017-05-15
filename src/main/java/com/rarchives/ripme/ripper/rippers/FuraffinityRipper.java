package com.rarchives.ripme.ripper.rippers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rarchives.ripme.utils.Utils;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.ripper.DownloadThreadPool;
import com.rarchives.ripme.utils.Base64;
import com.rarchives.ripme.utils.Http;

import javax.swing.*;

public class FuraffinityRipper extends AbstractHTMLRipper {
    public static final int IMAGE_SLEEP_TIME = 2000;
    static Map<String, String> cookies=null;
    static final String urlBase = "https://www.furaffinity.net";

    // Thread pool for finding direct image links from "image" pages (html)
    private DownloadThreadPool furaffinityThreadPool
                               = new DownloadThreadPool( "furaffinity");

    @Override
    public DownloadThreadPool getThreadPool() {
        return furaffinityThreadPool;
    }

    public FuraffinityRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getDomain() {
        return "furaffinity.net";
    }

    @Override
    public String getHost() {
        return "furaffinity";
    }
    @Override
    public boolean hasDescriptionSupport() {
        return true;
    }
    @Override
    public Document getFirstPage() throws IOException {
        if (cookies == null || cookies.size() == 0) {
            login();
        }

        return Http.url(url).cookies(cookies).get();
    }

    private void login() throws IOException {
        String newcookies = JOptionPane.showInputDialog("Gib da cookies pls b0ss");
        String[] cookiez = newcookies.split("; ");
        cookies = new HashMap<String, String>();
        for (String pairone: cookiez) {
            String[] pairtwo = pairone.split("=");
            cookies.put(pairtwo[0],pairtwo[1]);
        }
        /*
        String user = new String(Base64.decode("cmlwbWU="));
        String pass = new String(Base64.decode("cmlwbWVwYXNzd29yZA=="));

        Response loginPage = Http.url(urlBase + "/login/")
                                 .referrer(urlBase)
                                 .response();
        cookies = loginPage.cookies();

        Map<String,String> formData = new HashMap<String,String>();
        formData.put("action", "login");
        formData.put("retard_protection", "1");
        formData.put("name", user);
        formData.put("pass", pass);
        formData.put("login", "Login toÂ FurAffinity");




        Response doLogin = Http.url(urlBase + "/login/?ref=" + url)
                               .referrer(urlBase + "/login/")
                               .cookies(cookies)
                               .data(formData)
                               .method(Method.POST)
                               .response();
        cookies.putAll(doLogin.cookies());
        System.out.println(doLogin.body());
        */
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        // Find next page
        Elements nextPageUrl = doc.select("a.button-link.right");
        if (nextPageUrl.size() == 0) {
            throw new IOException("No more pages");
        }
        String nextUrl = urlBase + nextPageUrl.first().attr("href");

        sleep(500);
        Document nextPage = Http.url(nextUrl).cookies(cookies).get();

        Elements hrefs = nextPage.select("div#no-images");
        if (hrefs.size() != 0) {
            throw new IOException("No more pages");
        }
        return nextPage;
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        List<String> urls = new ArrayList<String>();
        Elements urlElements = page.select("figure.t-image > b > u > a");
        for (Element e : urlElements) {
            urls.add(urlBase + e.select("a").first().attr("href"));
        }
        return urls;
    }
    @Override
    public List<String> getDescriptionsFromPage(Document doc) {
        List<String> urls = new ArrayList<String>();
        Elements urlElements = doc.select("figure.t-image > b > u > a");
        for (Element e : urlElements) {
            urls.add(urlBase + e.select("a").first().attr("href"));
        }
        return urls;
    }
    @Override
    public int descSleepTime() {
        return 400;
    }
    public String[] getDescription(String url, Document page) {
        try {
            // Fetch the image page
            Response resp = Http.url(url)
                    .referrer(this.url)
                    .cookies(cookies)
                    .response();
            cookies.putAll(resp.cookies());

            // Try to find the description
            Element ele = resp.parse().select("td[class=alt1][width=\"70%\"]").first();
            if (ele == null) {
                logger.debug("No description at " + url);
                throw new IOException("No description found");
            }
            logger.debug("Description found!");
            Document documentz = resp.parse();
            documentz.outputSettings(new Document.OutputSettings().prettyPrint(false));
            ele.select("br").append("\\n");
            Elements paragraphs = ele.select("p");
            if (!paragraphs.isEmpty()) {
                if (paragraphs.size() > 0) {
                    paragraphs.remove(0);
                }
                if (paragraphs.size() > 0) {
                    paragraphs.prepend("\\n\\n");
                }
            }
            String tempPage = Jsoup.clean(ele.html().replaceAll("\\\\n", System.getProperty("line.separator")), "", Whitelist.none(), new Document.OutputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml));
            Element link = documentz.select("div.alt1 b a[href^=//d.facdn.net/]").first();
            if (link != null) {
                return new String[] {tempPage,fileNameFromURL(new URL("http:" + link.attr("href").substring(0, link.attr("href").lastIndexOf("."))))};
            }
            Element title = documentz.select("table.maintable[cellpadding=\"2\"] tbody tr td.cat b").first();
            if (title == null) {
                return new String[] {tempPage};
            }
            return new String[] {tempPage,title.text()};
        } catch (IOException ioe) {
            logger.info("Failed to get description " + url + " : '" + ioe.getMessage() + "'");
            return null;
        }
    }
    @Override
    public void downloadURL(URL url, int index) {
        furaffinityThreadPool.addThread(new FuraffinityDocumentThread(url));
        sleep(250);
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern
                .compile("^https?://www\\.furaffinity\\.net/gallery/([-_.0-9a-zA-Z]+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }
        throw new MalformedURLException("Expected furaffinity.net URL format: "
                + "www.furaffinity.net/gallery/username  - got " + url
                + " instead");
    }

    private class FuraffinityDocumentThread extends Thread {
        private URL url;

        public FuraffinityDocumentThread(URL url) {
            super();
            this.url = url;
        }

        @Override
        public void run() {
            try {
                Document doc = Http.url(url).cookies(cookies).get();
                // Find image
                Elements donwloadLink = doc.select("div.alt1 b a[href^=//d.facdn.net/]");
                if (donwloadLink.size() == 0) {
                    logger.warn("Could not download " + this.url);
                    return;
                }
                String link = "http:" + donwloadLink.first().attr("href");
                logger.info("Found URL " + link);
                addURLToDownload(new URL(link),"","",url.toExternalForm(),cookies);
                try {
                    sleep(IMAGE_SLEEP_TIME);
                } catch (InterruptedException e) {
                    logger.debug("Interrupted while finding images.");
                }
            } catch (IOException e) {
                logger.error("[!] Exception while loading/parsing " + this.url, e);
            }
        }
    }

}
