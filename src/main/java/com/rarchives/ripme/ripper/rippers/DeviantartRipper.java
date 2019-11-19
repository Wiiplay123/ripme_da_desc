package com.rarchives.ripme.ripper.rippers;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rarchives.ripme.ui.RipStatusMessage;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Base64;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.Utils;

public class DeviantartRipper extends AbstractHTMLRipper {
    String requestID;
    String galleryID;
    String username;
    String baseApiUrl = "https://www.deviantart.com/dapi/v1/gallery/";
    String csrf;
    Map<String, String> pageCookies = new HashMap<>();

    private static final int PAGE_SLEEP_TIME  = 3000,
                             IMAGE_SLEEP_TIME = 100,
            DOWNLOAD_SLEEP_TIME = 5000;

    boolean longerPage = false;
    private Map<String,String> cookies = new HashMap<String,String>();
    private Set<String> triedURLs = new HashSet<String>();

    public DeviantartRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "deviantart";
    }
    @Override
    public String getDomain() {
        return "deviantart.com";
    }
    @Override
    public boolean hasDescriptionSupport() {
        return true;
    }
    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException {
        String u = url.toExternalForm();
		if (u.contains("/gallery/")) {
            return url;
        }

        if (!u.endsWith("/gallery/") && !u.endsWith("/gallery") && !u.contains("/favorites") && !u.contains("/favourites")) {
											 
            if (!u.endsWith("/")) {
                u += "/gallery/?catpath=/";
            } else {
                u += "gallery/?catpath=/";
            }
							 
        }

        Pattern p = Pattern.compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9\\-]+)/favou?rites/([0-9]+)/*?$");
        Matcher m = p.matcher(url.toExternalForm());
        if (!m.matches()) {
            String subdir = "/";
            if (u.contains("catpath=scraps")) {
                subdir = "scraps";
            }
            u = u.replaceAll("\\?.*", "?catpath=" + subdir);
        }
        return new URL(u);
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        //URL url = new URL(url2.toString().replace("//deviantart","//www.deviantart"));
        Pattern p = Pattern.compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9\\-]+)(/gallery)?/?(\\?.*)?$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Root gallery
            if (url.toExternalForm().contains("catpath=scraps")) {
                return m.group(1) + "_scraps";
            }
            else {
                return m.group(1);
            }
        }
        p = Pattern.compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9\\-]+)/gallery/([0-9]+).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Subgallery
            return m.group(1) + "_" + m.group(2);
        }
        p = Pattern.compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9\\-]+)/favou?rites/([0-9]+)/.*?$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1) + "_faves_" + m.group(2);
        }
        p = Pattern.compile("^https?://www\\.deviantart\\.com/([a-zA-Z0-9\\-]+)/favou?rites/?$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Subgallery
            return m.group(1) + "_faves";
        }
        throw new MalformedURLException("Expected URL format: http://www.deviantart.com/username[/gallery/#####], got: " + url);
    }

    @Override
    public Document getFirstPage() throws IOException {
        // Login
        try {
            cookies = loginToDeviantart();
        } catch (Exception e) {
            logger.warn("Failed to login: ", e);
            cookies.put("agegate_state","1"); // Bypasses the age gate
        }
        return Http.url(this.url)
                   .cookies(cookies)
                   .get();
    }
    public String jsonToImage(Document page,String id) {
        Elements js = page.select("script[type=\"text/javascript\"]");
        for (Element tag : js) {
            if (tag.html().contains("window.__pageload")) {
                try {
                    String script = tag.html();
                    script = script.substring(script.indexOf("window.__pageload"));
                    if (script.indexOf(id) < 0) {
                        continue;
                    }
                    script = script.substring(script.indexOf(id));
                    // first },"src":"url" after id
                    script = script.substring(script.indexOf("},\"src\":\"") + 9, script.indexOf("\",\"type\""));
                    return script.replace("\\/", "/");
                } catch (StringIndexOutOfBoundsException e) {
                    logger.debug("Unable to get json link from " + page.location());
                }
            }
        }
        return null;
    }
    public static String replaceLast(String string, String toReplace, String replacement) {
        int pos = string.lastIndexOf(toReplace);
        if (pos > -1) {
            //logger.info(string.substring(0, pos)
            //        + replacement
            //        + string.substring(pos + toReplace.length(), string.length()));
            return string.substring(0, pos)
                    + replacement
                    + string.substring(pos + toReplace.length(), string.length());
        } else {
            return string;
        }
    }
    @Override
    public List<String> getURLsFromPage(Document page) {
        List<String> imageURLs = new ArrayList<String>();
        // Iterate over all thumbnails
        other: for (Element thumb : page.select("div.zones-container span.thumb")) {
            if (isStopped()) {
                break;
            }
            Element img = thumb.select("a > img").get(0);
            System.out.println("div.zones-container span.thumb = " + thumb);
            if (img.attr("transparent").equals("false")) {
                continue; // a.thumbs to other albums are invisible
            }
            // Get full-sized image via helper methods
            String fullSize = null;
            try {
                if (Utils.getConfigBoolean("wixskip", true)) {
                    logger.info("Checking Wix duplicates");
                    // Check if file without fullview name already exists and skip if so
                    Pattern p = Pattern.compile("(/f/[0-9a-z]{8}-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{12}/)(.*?\\.)(.*?)(\\?|/)");
                    Pattern p2 = Pattern.compile("(strp/)(.*\\.)(.*)(\\?)");
                    Pattern p3 = Pattern.compile("(/f/[0-9a-z]{8}-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{12}/)(.*?\\.)(.*?)(/)");
                    Matcher m = p2.matcher(thumb.attr("data-super-full-img"));
                    Matcher m2 = p2.matcher(thumb.attr("data-super-img"));
                    Matcher m3 = p.matcher(thumb.attr("data-super-full-img"));
                    Matcher m4 = p.matcher(thumb.attr("data-super-img"));
                    Matcher m5 = p.matcher(img.attr("src"));
                    if (m.find()) {
                        // Root gallery
                        try {
                            //logger.info("Match: " + m.group(2));
                            //logger.info("Match 2: " + workingDir.getCanonicalPath() + "" + File.separator + m.group(2).replace("-fullview.",".") + m.group(3));
                            logger.info("Finding " + workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m.group(2),"_","-"));
                            if (new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m.group(2),"_","-") + m.group(3)).exists() || new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m.group(2),"_","-").replace("-fullview.",".") + m.group(3)).exists() || new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m.group(2),"_","-").replace("-fullview.",".") + "png").exists() || new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m.group(2),"_","-").replace("-fullview.",".") + "gif").exists()) {
                                logger.info("Image from " + thumb.attr("data-super-full-img") + " already exists as original quality, no button required.");
                                continue;
                            } else {
                                if (m2.find()) {
                                    if (new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m2.group(2), "_", "-") + m2.group(3)).exists() || new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m2.group(2), "_", "-").replace("-pre.", ".") + m2.group(3)).exists() || new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m2.group(2), "_", "-").replace("-pre.", ".") + "png").exists() || new File(workingDir.getCanonicalPath() + "" + File.separator + replaceLast(m2.group(2), "_", "-").replace("-pre.", ".") + "gif").exists()) {
                                        logger.info("Image from " + thumb.attr("data-super-full-img") + " already exists as original quality, no button required.");
                                        continue;
                                    } else {
                                        logger.info("Couldn't find " + thumb.attr("data-super-full-img") + " existing as original quality, button required.");
                                    }
                                } else {
                                    logger.info("Couldn't find " + thumb.attr("data-super-full-img") + " existing as original quality, button required.");
                                }
                            }
                        } catch (IOException ioe) {
                            logger.warn("Unable to check fullview filename for original duplicates.");
                        }
                    }
                    //logger.info("m3: " + m3.find());
                    //logger.info("m5: " + m5.find());
                    //logger.info("thumb: " + thumb.attr("data-super-img"));
                    //logger.info("img: " + img.attr("src"));
                    if (m3.find()) {
                        String[] Listz = new File(workingDir.getCanonicalPath()).list();
                        //logger.info(Listz);
                        if (Listz != null) {
                            for (int i = 0; i < Listz.length; i++) {
                                if (Listz[i].contains(m3.group(2).substring(0,m3.group(2).indexOf("-")) + ".") && m3.group(3).equals(Listz[i].substring(Listz[i].length() - m3.group(3).length(), Listz[i].length()))) {
                                    logger.info(m3.group(2) + m3.group(3) + " found");
                                    continue other;
                                }
                            }
                        }
                    } else if (m4.find()) {
                        String[] Listz = new File(workingDir.getCanonicalPath()).list();
                        if (Listz != null) {
                            for (int i = 0; i < Listz.length; i++) {
                                if (Listz[i].contains(m3.group(2).substring(0,m4.group(2).indexOf("-")) + ".") && m4.group(3).equals(Listz[i].substring(Listz[i].length() - m4.group(3).length(), Listz[i].length()))) {
                                    logger.info(m4.group(2) + m4.group(3) + " found");
                                    continue other;
                                }
                            }
                        }
                    } else if (m5.find()) {
                        String[] Listz = new File(workingDir.getCanonicalPath()).list();
                        if (Listz != null) {
                            for (int i = 0; i < Listz.length; i++) {
                                //logger.info("group 2 substring: " + m5.group(2).substring(0,m5.group(2).indexOf("-")) + ".");
                                //logger.info("group 3: " + m5.group(3));
                                //logger.info("swag: " + Listz[i].substring(Listz[i].length() - m5.group(3).length(), Listz[i].length()));
                                if (Listz[i].contains(m5.group(2).substring(0,m5.group(2).indexOf("-")) + ".") && m5.group(3).equals(Listz[i].substring(Listz[i].length() - m5.group(3).length(), Listz[i].length()))) {
                                    logger.info(m5.group(2) + m5.group(3) + " found");
                                    continue other;
                                }
                            }
                        }
                    }
                    logger.info("COULD NOT FIND MATCHES");
                } else if (Utils.getConfigBoolean("wixskip", true)){
                    logger.info("Could not check " + thumb.attr("data-super-full-img") + " for Wix duplicates.");
                }
                if (Utils.getConfigBoolean("advskip", false) && new File(workingDir.getCanonicalPath() + "" + File.separator + fileNameFromURL(new URL(thumb.attr("data-super-img")))).exists() && new File(workingDir.getCanonicalPath() + "" + File.separator + fileNameFromURL(new URL(thumb.attr("data-super-img")))).isFile()) {
                    logger.info("Image from " + thumb.attr("data-super-img") + " (data-super-img) already exists.");
                    triedURLs.add(thumb.attr("data-super-img"));
                    imageURLs.add(thumb.attr("data-super-img"));
                    continue;
                }
            }
            catch (IOException ioe2) {
                logger.info("EPIC FAIL");
            }
            if ((thumb.attr("data-super-full-img").contains("//orig") || thumb.attr("data-super-full-img").contains("//api-da")) && Utils.getConfigBoolean("litskip",false)) {
                fullSize = thumb.attr("data-super-full-img");
            } else {
                String spanUrl = thumb.attr("href");
                String fullSize1 = null;
                if (Utils.getConfigBoolean("litskip",false)) {
                     fullSize1 = jsonToImage(page, spanUrl.substring(spanUrl.lastIndexOf('-') + 1));
                }
                if (fullSize1 == null || !(fullSize1.contains("//orig") || fullSize1.contains("//api-da"))) {
                    try {
                        if (fullSize1 != null) {
                            if (Utils.getConfigBoolean("advskip", false) && new File(workingDir.getCanonicalPath() + "" + File.separator + fileNameFromURL(new URL(fullSize1))).exists() && new File(workingDir.getCanonicalPath() + "" + File.separator + fileNameFromURL(new URL(fullSize1))).isFile()) {
                                logger.info("Image from " + fullSize1 + " (data-super-full-img) already exists.");
                                continue;
                            }
                        }
                    }
                    catch (IOException ioe) {
                        logger.warn("Unable to check filename for duplicates.");
                    }
                    System.out.println("Heading to " + spanUrl);
                    fullSize = smallToFull(img.attr("src"), spanUrl);
                }
                if (fullSize == null && fullSize1 != null) {
                    fullSize = fullSize1;
                }
            }
            if (fullSize == null) {
                if (thumb.attr("data-super-full-img") != null) {
                    fullSize = thumb.attr("data-super-full-img");
                } else if (thumb.attr("data-super-img") != null) {
                    fullSize = thumb.attr("data-super-img");
                } else {
                    continue;
                }
            } else {
                String[] urlz = fullSize.split("wiiplus1337");
                for (String fullSizez : urlz) {
                    if (triedURLs.contains(fullSizez)) {
                        logger.warn("Already tried to download " + fullSizez);
                        continue;
                    }
                    triedURLs.add(fullSizez);
                    imageURLs.add(fullSizez);
                }
            }
            if (isThisATest()) {
                // Only need one image for a test
                break;
            }
        }
        return imageURLs;
    }
    @Override
    public List<String> getDescriptionsFromPage(Document page) {
        List<String> textURLs = new ArrayList<String>();
        // Iterate over all thumbnails
        other1: for (Element thumb : page.select("div.zones-container span.thumb")) {
            Element img1 = thumb.select("a > img").get(0);
            String fsize = null;
            logger.info(thumb.attr("href"));
            if ((thumb.attr("data-super-full-img").contains("//orig") || thumb.attr("data-super-full-img").contains("//api-da")) && Utils.getConfigBoolean("litskip",false)) {
                fsize = thumb.attr("data-super-full-img");
                try {
                    String dname = fileNameFromURL(new URL(fsize));
                    dname = dname.substring(0, dname.lastIndexOf(".")) + ".txt";
                    dname = dname.replace("-pre.",".");
                    dname = dname.replace("-fullview.",".");
                    if (!dname.contains("-")) {
                        dname = replaceLast(dname,"_","-");
                    }
                    if (Utils.getConfigBoolean("advskip", false) && new File(workingDir.getCanonicalPath() + "" + File.separator + dname).exists() && new File(workingDir.getCanonicalPath() + "" + File.separator + dname).isFile()) {
                        logger.info("Image from " + fsize + " (data-super-full-img) already exists, skipping description.");
                        continue;
                    }
                } catch (IOException ioe) {
                    logger.warn("Unable to check filename for duplicates.");
                }
            } else {
                String spanUrl = thumb.attr("href");
                String fsize1 = null;
                if (Utils.getConfigBoolean("litskip", false)) {
                    fsize1 = jsonToImage(page, spanUrl.substring(spanUrl.lastIndexOf('-') + 1));
                }
                if (fsize1 != null) {
                    try {
                        if (fsize1 != null) {
                            String dname = fileNameFromURL(new URL(fsize1));
                            dname = dname.substring(0, dname.lastIndexOf(".")) + ".txt";
                            dname.replace("-pre.",".");
                            dname = dname.replace("-fullview.",".");
                            if (!dname.contains("-")) {
                                dname = replaceLast(dname,"_","-");
                            }
                            if (Utils.getConfigBoolean("advskip", false) && new File(workingDir.getCanonicalPath() + "" + File.separator + dname).exists() && new File(workingDir.getCanonicalPath() + "" + File.separator + dname).isFile()) {
                                logger.info("Image from " + fsize1 + " (json) already exists, skipping description.");
                                continue;
                            }
                        }
                    } catch (IOException ioe) {
                        logger.warn("Unable to check filename for duplicates.");
                    }
                }
            }
            Pattern p3 = Pattern.compile("(/f/[0-9a-z]{8}-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{12}/)(.*?\\.)(.*?)(/)");
            Matcher m5 = p3.matcher(img1.attr("src"));
            if (m5.find()) {
                try {
                    String[] Listz = new File(workingDir.getCanonicalPath()).list();
                    if (Listz != null) {
                        for (int i = 0; i < Listz.length; i++) {
                            //logger.info("group 2 substring: " + m5.group(2).substring(0,m5.group(2).indexOf("-")) + ".");
                            //logger.info("group 3: " + m5.group(3));
                            //logger.info("swag: " + Listz[i].substring(Listz[i].length() - m5.group(3).length(), Listz[i].length()));
                            if (Listz[i].contains(m5.group(2).substring(0, m5.group(2).indexOf("-")) + ".") && m5.group(3).equals(Listz[i].substring(Listz[i].length() - m5.group(3).length(), Listz[i].length()))) {
                                logger.info(m5.group(2) + "htm found");
                                continue other1;
                            }
                        }
                    }
                } catch (IOException ioe) {
                    logger.info("Could not check descriptions for Wix duplicates.");
                }
            }
            if (isStopped()) {
                break;
            }
            Element img = thumb.select("img").get(0);
            if (img.attr("transparent").equals("false")) {
                continue; // a.thumbs to other albums are invisible
            }
            textURLs.add(thumb.attr("href"));

        }
        return textURLs;
    }
    @Override
    public Document getNextPage(Document page) throws IOException {
        if (isThisATest()) {
            return null;
        }
        Elements nextButtons = page.select("link[rel=\"next\"]");
        if (nextButtons.size() == 0) {
            if (page.select("link[rel=\"prev\"]").size() == 0) {
                throw new IOException("No next page found");
            } else {
                throw new IOException("Hit end of pages");
            }
        }
        Element a = nextButtons.first();
        String nextPage = a.attr("href");
        if (nextPage.startsWith("/")) {
            nextPage = "http://" + this.url.getHost() + nextPage;
        }
        if (!sleep(PAGE_SLEEP_TIME)) {
            if (longerPage) {
                if (!sleep(185000)) {
                    longerPage = false;
                    throw new IOException("Interrupted while waiting to load next page: " + nextPage);
                } else {
                    longerPage = false;
                    return page;
                }
            }
            throw new IOException("Interrupted while waiting to load next page: " + nextPage);
        }
        logger.info("Found next page: " + nextPage);
        return Http.url(nextPage)
                   .cookies(cookies).retries(5).get();
    }

    @Override
    public boolean keepSortOrder() {
         // Don't keep sort order (do not add prefixes).
         // Causes file duplication, as outlined in https://github.com/4pr0n/ripme/issues/113
        return false;
    }

    @Override
    public void downloadURL(URL url, int index) {
        //downloadURL(url, index, "");
        String imageURL = url.toString();
        if (imageURL.contains("wiiplus9001")) {
            try {
                logger.info("Trying to get it");
                downloadURL(new URL(imageURL.split("wiiplus9001")[0]), index, imageURL.split("wiiplus9001")[1]);
            } catch (MalformedURLException why) {
                logger.info("Malformed URL exception at modified downloadURL: " + imageURL);
            }
        } else {
            downloadURL(url, index, "");
        }
    }

    public void downloadURL(URL url, int index, String nameOverride) {
        addURLToDownload(url, getPrefix(index), "", this.url.toExternalForm(), cookies, nameOverride);
        sleep(IMAGE_SLEEP_TIME);
    }

    /**
     * Tries to get full size image from thumbnail URL
     * @param thumb Thumbnail URL
     * @param throwException Whether or not to throw exception when full size image isn't found
     * @return Full-size image URL
     * @throws Exception If it can't find the full-size URL
     */
    public static String thumbToFull(String thumb, boolean throwException) throws Exception {
        thumb = "http://" + thumb.substring(thumb.indexOf("origin()/") + 9).replaceFirst("/",".deviantart.net/");
        //thumb = thumb.replace("http://th", "http://fc");
        List<String> fields = new ArrayList<String>(Arrays.asList(thumb.split("/")));
        fields.remove(4);
        if (!fields.get(4).equals("f") && throwException) {
            // Not a full-size image
            throw new Exception("Can't get full size image from " + thumb);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                result.append("/");
            }
            result.append(fields.get(i));
        }
        return result.toString();
    }

    @Override
    public boolean saveText(URL url, String subdirectory, String text, int index, String fileName) {
        return saveText(url,subdirectory,text,index,fileName,"_description.htm");
    }
    /**
     * Attempts to download description for image.
     * Comes in handy when people put entire stories in their description.
     * If no description was found, returns null.
     * @param url The URL the description will be retrieved from
     * @param page The gallery page the URL was found on
     * @return A String[] with first object being the description, and the second object being image file name if found.
     */
    public static String CSSString = "a.title{display:block} .devwatch {display:none} span.dev-title-avatar {float:left;margin:0 8px 5px 0} .mature-tag {float:right; color:#596674} h1 {font: bold 16pt Trebuchet MS,sans-serif} body {color:#EEEEEE; text-shadow: 1px 1px 1px #383B38; background:#444444; font-family:Verdana,sans-serif; font-size: 12px} .dev-about-breadcrumb a.h:hover, h1 small a.username:hover {color:#DDFFFF !important; text-decoration:none} .by, .dev-about-breadcrumb a.h, h1 small a.username {color:#AAAAAA !important; text-decoration:none!important} h1 small a.username {color:#337287!important; text-decoration:none} a.discoverytag:hover {color:#BBBBDD !important; text-decoration:none} body a:not(.text-ctrl), .dev-about-breadcrumb a, div.dev-page-view .dev-title-container a:hover {color:#EEEEEE!important;text-decoration:none} div.text-ctrl a {text-decoration:underline} div.text-ctrl a:hover {color:#DDFFFF!important} h1 small {font-size: 70%;letter-spacing: .02em} h1 {line-height: 1.175} div.dev-about-cat-cc, div.dev-about-tags-cc, div.dev-title-author, div.title {padding-left:58px} body > div.text-ctrl {margin-top:10px; border-top: 1px solid #778187 !important} body > div.text-ctrl > div.text.block {padding-top:10px; border-top: 1px solid #b1b7bc !important} .dev-about-cat-cc, span.cc-copy, a.discoverytag {font-size: 8.25pt} h1 {margin-bottom:0; letter-spacing: -1} .dev-about-breadcrumb {margin-right:12px} span.cc-copy {color:#97a2a0}";
    @Override
    public String[] getDescription(String url,Document page) {
        if (isThisATest()) {
            return null;
        }
        try {
            // Fetch the image page
            Response resp = Http.url(url)
                                .referrer(this.url)
                                .cookies(cookies)
                                .response();
            cookies.putAll(resp.cookies());

            // Try to find the description
            Document documentz = resp.parse();
            Element ele = documentz.select("div.dev-description").first();
            Element ele2 = documentz.select(".journal-wrapper2 div.text").first();
            Element titleelement = documentz.select("div.dev-title-container").first();
            String fullSize = null;
            if (ele == null && ele2 == null && titleelement == null) {
                throw new IOException("No text content found");
            } else {
                String saveCSS = workingDir.getCanonicalPath()
                        + ""
                        + File.separator
                        + "deviantartGray.css";
                try {
                if (!(new File(saveCSS).exists())) {
                    saveText(new URL(url), "", CSSString , 0, "deviantartGray", ".css");

                }
                } catch (Exception oof) {
                    logger.info("Couldn't save CSS file.");
                }
            }
            if (ele != null) {
                documentz.outputSettings(new Document.OutputSettings().prettyPrint(false));
                //ele.select("br").append("\\n");
                //ele.select("p").prepend("\\n\\n");
                Element thumb = page.select("div.zones-container span.thumb[href=\"" + url + "\"]").get(0);
                if (!thumb.attr("data-super-full-img").isEmpty()) {
                    fullSize = thumb.attr("data-super-full-img");
                    String[] split = fullSize.split("/");
                    fullSize = split[split.length - 1];
                } else {
                    String spanUrl = thumb.attr("href");
                    fullSize = jsonToImage(page, spanUrl.substring(spanUrl.lastIndexOf('-') + 1));
                    if (fullSize != null) {
                        String[] split = fullSize.split("/");
                        fullSize = split[split.length - 1];
                    }
                }
                if (!thumb.attr("data-super-full-img").contains("_")) {
                    if (thumb.attr("data-super-img").contains("_")) {
                        fullSize = thumb.attr("data-super-img");
                        String[] split = fullSize.split("/");
                        fullSize = split[split.length - 1];
                    }
                }
            }
            if (ele2 != null) {
                String title = fileNameFromURL(new URL(url));
                String htmtitle = null;
                Element ele3 = documentz.select("a.title").first();
                if (ele3 != null) {
                    htmtitle = ele3.text();
                }
                Element ele4 = documentz.select("div.metadata").first();
                String html = ele2.outerHtml();
                html = (ele4 != null ? ele4.outerHtml() : "") + html;
                String saveAs = workingDir.getCanonicalPath()
                        + ""
                        + File.separator
                        + title
                        + ".htm";
                if (Utils.getConfigBoolean("file.overwrite", false) || !(new File(saveAs).exists())) {
                    logger.info("Got journal from " + url);
                    saveText(new URL(url), "","<!DOCTYPE html><html><head><meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\" /><meta charset=\"utf-8\" /><title>" + (htmtitle != null ? htmtitle : title) + "</title></head><body>" + html + "</body></html>" , 0, title, ".htm");
                    RipStatusMessage msg = new RipStatusMessage(RipStatusMessage.STATUS.DOWNLOAD_COMPLETE, Utils.removeCWD(saveAs));
                    observer.update(this, msg);
                } else {
                    logger.debug("Journal from " + url.toString() + " already exists.");
                    RipStatusMessage msg = new RipStatusMessage(RipStatusMessage.STATUS.DOWNLOAD_WARN, Utils.removeCWD(saveAs));
                    observer.update(this, new RipStatusMessage(RipStatusMessage.STATUS.DOWNLOAD_WARN, url + " already saved as " + saveAs));
                }
            }
            String pageStr = "<!DOCTYPE html><meta http-equiv=\"content-type\" content=\"text/html; charset=ISO-8859-1\" /><link href=\"deviantartGray.css\" rel=\"stylesheet\" type=\"text/css\" />" + titleelement.html() + ele.html();
            if (fullSize == null) {
                //return new String[] {Jsoup.clean(ele.html().replaceAll("\\\\n", System.getProperty("line.separator")), "", Whitelist.none(), new Document.OutputSettings().prettyPrint(false))};
                return new String[] {pageStr};
            }
            if (fullSize.contains("?")) {
                fullSize = fullSize.substring(0, fullSize.lastIndexOf("?") - 1);
            }
            fullSize = fullSize.substring(0, fullSize.lastIndexOf("."));
            //logger.info(" Fixing " + fullSize);
            fullSize = fullSize.replace("-pre","");
            fullSize = fullSize.replace("-fullview","");
            if (!fullSize.contains("-")) {
                fullSize = replaceLast(fullSize,"_","-");
            }
            //logger.info("Desc " + fullSize);
            return new String[] {pageStr,fullSize};
            // TODO Make this not make a newline if someone just types \n into the description.
        } catch (IOException ioe) {
                logger.info("Failed to get description at " + url + ": '" + ioe.getMessage() + "'");
                return null;
        }
    }

    /**
     * If largest resolution for image at 'thumb' is found, starts downloading
     * and returns null.
     * If it finds a larger resolution on another page, returns the image URL.
     * @param thumb Thumbnail URL
     * @param page Page the thumbnail is retrieved from
     * @return Highest-resolution version of the image based on thumbnail URL and the page.
     */
    public String smallToFull(String thumb, String page) {
        try {
            Response resp;
            // Fetch the image page
            try {
                resp = Http.url(page)
                        .referrer(this.url)
                        .cookies(cookies)
                        .response();
                cookies.putAll(resp.cookies());
            } catch (Exception E) {
                resp = Http.url(page)
                        .referrer(this.url)
                        .cookies(cookies)
                        .response();
                cookies.putAll(resp.cookies());
            }
            Document doc = resp.parse();
            Elements els = doc.select("img.dev-content-full");
            //System.out.println(els.size());
            String fsimage = null;
            // Get the largest resolution image on the page
            if (els.size() > 0) {
                // Large image
                fsimage = els.get(0).attr("src");
                logger.info("Found large-scale: " + fsimage);
                if (fsimage.contains("//orig") || fsimage.contains("//api-da")) {
                    if (doc.select("span.crumb a.h[href*=\"literature\"]").isEmpty() && !containsMulti(doc.select(".dev-page-download").attr("href"),".pdf",".doc",".rtf",".txt",".zip",".7z",".psd")) {
                        return fsimage;
                    }
                }
            }
            // Try to find the download button
            els = doc.select("a.dev-page-download");
            if (els.size() > 0) {
                // Full-size image
                String downloadLink = els.get(0).attr("href");
                logger.info("Found download button link: " + downloadLink);
                HttpURLConnection con = (HttpURLConnection) new URL(downloadLink).openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("Referer",this.url.toString());
                String cookieString = "";
                for (Map.Entry<String, String> entry : cookies.entrySet()) {
                    cookieString = cookieString + entry.getKey() + "=" + entry.getValue() + "; ";
                }
                cookieString = cookieString.substring(0,cookieString.length() - 1);
                con.setRequestProperty("Cookie",cookieString);
                con.setRequestProperty("User-Agent",this.USER_AGENT);
                con.setInstanceFollowRedirects(true);
                con.connect();
                int code = con.getResponseCode();
                String location = con.getURL().toString();
                String headerStuff = con.getHeaderField("content-disposition");
                logger.info(con.getHeaderFields());
                String newName = "";
                if (headerStuff != null) {
                    newName = headerStuff.split("\'\'")[1];
                    if (!newName.contains("-")) {
                        newName = replaceLast(newName,"_","-");
                    }
                    //logger.info("Got SUPERNAME: " + newName);
                } else {
                    logger.info("IT'S NULL?");
                }
                con.disconnect();
                sleep(DOWNLOAD_SLEEP_TIME);
                if (code == 403 || code == 405 || code == 401) {
                //    return smallToFull(thumb, page);
                    longerPage = true;
                }
                //logger.info("Got download button: " + location);
                if (location.contains("//orig") || location.contains("api-da")) {
                    if (fsimage != null) {
                        if (fsimage.contains("orig") || fsimage.contains("api-da")) {
                            fsimage = fsimage + "wiiplus1337" + location + (newName.isEmpty() ?  "" : ("wiiplus9001" + newName));
                        } else {
                            fsimage = location + (newName != "" ? "wiiplus9001" + newName : "");
                        }
                    } else {
                        fsimage = location + (newName != "" ? "wiiplus9001" + newName : "");
                    }
                    logger.info("Found image download from button: " + location);
                }
            }
            if (fsimage != null) {
                return fsimage;
            }
            throw new IOException("No download page found");
        } catch (IOException ioe) {
            try {
                logger.info("Failed to get full size download image at " + page + " : '" + ioe.getMessage() + "'");
                String lessThanFull = thumbToFull(thumb, false);
                logger.info("Falling back to less-than-full-size image " + lessThanFull);
                return lessThanFull;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Logs into deviant art. Required to rip full-size NSFW content.
     * @return Map of cookies containing session data.
     */
    private Map<String, String> loginToDeviantart() throws IOException {
        // Populate postData fields

        String newcookies = Utils.getConfigString("deviantart.cookies", "");
        cookies = new HashMap<String, String>();
        if (Utils.getConfigBoolean("deviantart.usecookies",false)) {
            if (!newcookies.isEmpty()) {
                String[] cookiez = newcookies.split("; ");
                for (String pairone : cookiez) {
                    String[] pairtwo = pairone.split("=");
                    cookies.put(pairtwo[0], pairtwo[1]);
                }
            } else {
                System.out.println("Login failed.");
            }
            return cookies;
        } else {


        Map<String,String> postData = new HashMap<String,String>();
        String username = Utils.getConfigString("deviantart.username", new String(Base64.decode("Z3JhYnB5")));
        String password = Utils.getConfigString("deviantart.password", new String(Base64.decode("ZmFrZXJz")));
        if (username == null || password == null) {
            throw new IOException("could not find username or password in config");
        }
        Response resp = Http.url("http://www.deviantart.com/")
                            .response();
        for (Element input : resp.parse().select("form#form-login input[type=hidden]")) {
            postData.put(input.attr("name"), input.attr("value"));
        }
        postData.put("username", username);
        postData.put("password", password);
        postData.put("remember_me", "1");

        // Send login request
        resp = Http.url("https://www.deviantart.com/users/login")
                    .userAgent(USER_AGENT)
                    .data(postData)
                    .cookies(resp.cookies())
                    .method(Method.POST)
                    .response();

        // Assert we are logged in
        if (resp.hasHeader("Location") && resp.header("Location").contains("password")) {
            // Wrong password
            throw new IOException("Wrong password");
        }
        if (resp.url().toExternalForm().contains("bad_form")) {
            throw new IOException("Login form was incorrectly submitted");
        }
        if (resp.cookie("auth_secure") == null ||
            resp.cookie("auth") == null) {
            throw new IOException("No auth_secure or auth cookies received");
        }
        // We are logged in, save the cookies
        return resp.cookies();

        }
    }
}
