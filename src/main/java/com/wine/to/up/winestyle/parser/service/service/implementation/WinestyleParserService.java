package com.wine.to.up.winestyle.parser.service.service.implementation;

import com.wine.to.up.winestyle.parser.service.domain.entity.ImageAlcohol;
import com.wine.to.up.winestyle.parser.service.service.ParserService;
import com.wine.to.up.winestyle.parser.service.controller.exception.ServiceIsBusyException;
import com.wine.to.up.winestyle.parser.service.domain.entity.Wine;
import com.wine.to.up.winestyle.parser.service.service.DocumentService;
import com.wine.to.up.winestyle.parser.service.service.WineService;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Класс-парсер.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WinestyleParserService implements ParserService {
    private final WineService wineService;
    private final DocumentService documentService;
    private volatile Boolean iAmUsed = false;

    String mainUrl = "https://spb.winestyle.ru";
    String wineUrl = "/wine/wines_ll/";

    // Start parsing job in a separate thread
    public void startParsingJob(String alcoholType) throws ServiceIsBusyException {
        if (!iAmUsed) {
            if (alcoholType.equals("wine")) {
                Thread newThread = new Thread(() -> {
                    try {
                        parseByPages(wineUrl);
                    } catch (InterruptedException e) {
                        log.error("Thread is sleeping!", e);
                    }
                });
                newThread.start();
            }
        } else {
            throw ServiceIsBusyException.createWith("parsing job is already running.");
        }
    }

    // At 00:00; every day
    @Scheduled(cron = "${scheduler.cron.expression}")
    public void onScheduleParseWinePages(){
        if (!iAmUsed) {
            try {
                parseByPages(wineUrl);
            } catch (InterruptedException e) {
                log.error("Error on schedule with parsing wines pages!", e);
            }
        }
    }

    // Page by page parsing
    private void parseByPages(String relativeUrl) throws InterruptedException {
        iAmUsed = true;
        String alcoholUrl = mainUrl + relativeUrl;

        Document mainDoc = documentService.getJsoupDocument(alcoholUrl);
        Document productDoc;

        int pages = documentService.pagesNumber(mainDoc);

        for (int i = 2; i <= pages; i++) {
            Elements productElements = mainDoc.getElementsByClass("item-block");

            for (Element productElement : productElements) {

                String urlToProductPage = productElement.selectFirst("a").attr("href");

                if (wineService.getWineByUrl(urlToProductPage) == null){
                    Wine.WineBuilder wineBuilder = Wine.builder().url(urlToProductPage);

                    parseMainPageInfo(productElement, wineBuilder);

                    productDoc = documentService.getJsoupDocument(mainUrl + urlToProductPage);
                    parseProductPageInfo(productDoc, wineBuilder);

                    wineService.add(wineBuilder.build());
                } else {
                    updatePriceAndRating(productElement, urlToProductPage);
                }

                if ((wineService.getWineByUrl(urlToProductPage)).getImage() == null){
                    productDoc = documentService.getJsoupDocument(mainUrl + urlToProductPage);
                    String imageUrl = parseImageUrl(productDoc.selectFirst(".left-aside"));
                    updateImageByProductUrl(imageUrl, urlToProductPage);
                }
            }
            mainDoc = documentService.getJsoupDocument(alcoholUrl + "?page=" + i);
        }
        iAmUsed = false;
    }

    /**
     * Main page parsing
     * @param productElement Контейнер, в котором лежинт информация о вине.
     * @param builder Билдер сущности вина.
     */
    private void parseMainPageInfo(Element productElement, Wine.WineBuilder builder) {
        String name;
        BigDecimal price;
        Double rating;
        Integer cropYear;
        String manufacturer;
        String brand;
        String color;
        String sugar;
        String country;
        String region;
        Double volume;
        String strength;
        String grape;

        name = parseName(productElement);
        cropYear = parseCropYear(name);
        price = parsePrice(productElement);

        // Block containing product's rest part of information
        Element infoContainer = productElement.selectFirst(".info-container");

        manufacturer = parseManufacturer(infoContainer);
        brand = parseBrand(infoContainer);
        volume = parseVolume(infoContainer);
        strength = parseStrength(infoContainer);
        grape = parseGrape(infoContainer);
        rating = parseWinestyleRating(infoContainer);

        String[] countryAndRegions = parseCountryAndRegions(infoContainer);
        country = countryAndRegions[0];
        region = countryAndRegions[1];

        String[] colorAndSugar = parseColorAndSugar(productElement);
        color = colorAndSugar[0];
        sugar = colorAndSugar[1];

        builder
                .name(name).price(price).rating(rating).cropYear(cropYear).manufacturer(manufacturer).brand(brand)
                .color(color).sugar(sugar).country(country).region(region).volume(volume).strength(strength)
                .grape(grape);
    }

    /**
     * Product's page parsing
     * @param doc Страница в формате документа, с помощью класса DocumentService {@link com.wine.to.up.winestyle.parser.service.service.implementation.WinestyleDocumentService}.
     * @param builder Билдер сущности вина. 
     */
    private void parseProductPageInfo(Document doc, Wine.WineBuilder builder) {
        ImageAlcohol image;
        String imageUrl;
        String taste;
        String aroma;
        String foodPairing;
        String description;

        // Block containing product's image
        Element leftBlock = doc.selectFirst(".left-aside");

        imageUrl = parseImageUrl(leftBlock);
        image = downloadImageByUrl(imageUrl);

        // Block containing product's tasting notes
        Element articleBlock = doc.selectFirst(".articles-col");

        taste = parseTaste(articleBlock);
        aroma = parseAroma(articleBlock);
        foodPairing = parseFoodPairing(articleBlock);

        // Block containing product's description
        Element descriptionBlock = doc.selectFirst(".articles-container.desc");

        description = parseDescription(descriptionBlock);

        builder.imageUrl(imageUrl).image(image).taste(taste).aroma(aroma).foodPairing(foodPairing).description(description);
    }

    /**
     * Update potentially modified data
     * @param el Контейнер, в котором лежит цена и рейтинг вина.
     * @param url Строка-ссылка на страницу вина.
     */
    private void updatePriceAndRating(Element el, String url){
        BigDecimal priceForUpdate = parsePrice(el);
        Double ratingForUpdate = parseWinestyleRating(el);

        wineService.updatePrice(priceForUpdate, url);
        wineService.updateRating(ratingForUpdate, url);
    }

    /**
     * Парсер названия вина.
     * @param el Контейнер, в котором лежит название вина.
     * @return Название вина.
     */
    private String parseName(Element el){
        Element nameElement = el.selectFirst(".title");
        return nameElement.attr("data-prodname");
    }

    /**
     * Парсер цены вина.
     * @param el Контейнер, в котором лежит стоимость вина.
     * @return Стоимость вина ИЛИ null, если её нет.
     */
    private BigDecimal parsePrice(Element el){
        try {
            String priceValue = el.selectFirst(".price").ownText();
            priceValue = priceValue.replaceAll(" ", "");
            return BigDecimal.valueOf(Double.parseDouble(priceValue));
        } catch (Exception ex) {
            log.warn("product's price is not specified");
            return null;
        }
    }

    /**
     * Парсер рейтинга вина.
     * @param el Контейнер, в котором лежит рейтинг вина.
     * @return Рейтинг вина ИЛИ null, если его нет.
     */
    private Double parseWinestyleRating(Element el) {
        try {
            String ratingElement = el.selectFirst(".rating-text").child(1).text();
            return Double.parseDouble(ratingElement) / 2.;
        } catch (Exception ex) {
            log.warn("product's winestyle's rating is not specified");
            return null;
        }
    }

    /**
     * Парсер вкуса вина.
     * @param el Контейнер, в котором лежит описание вкуса вина.
     * @return Вкус вина ИЛИ null, если нет его описания.
     */
    private String parseTaste(Element el) {
        try {
            Element tasteElement = el.selectFirst("span:contains(Вкус)").nextElementSibling();
            return tasteElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's taste is not specified");
            return null;
        }
    }

    /**
     * Парсер аромата вина.
     * @param el Контейнер, в котором описан аромат вина.
     * @return Аромат ИЛИ null, если нет его описания.
     */
    private String parseAroma(Element el) {
        try {
            Element aromaElement = el.selectFirst("span:contains(Аром)").nextElementSibling();
            return aromaElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's aroma is not specified");
            return null;
        }
    }

    /**
     * Парсер сочетания вина с блюдами. 
     * @param el Контейнер, в котором перечислены хорошие сочетания вина с блюдами.
     * @return Строку сочетаний ИЛИ null, если их нет.
     */
    private String parseFoodPairing(Element el) {
        try {
            Element foodPairingElement = el.selectFirst("span:contains(Гаст)").nextElementSibling();
            return foodPairingElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's food pairing is not specified");
            return null;
        }
    }

    /**
     * Парсер винограда, свойство: год сбора.
     * @param name Контейнер, в котором лежит год сбора винограда.
     * @return Год сбора ИЛИ null, если его нет.
     */
    private Integer parseCropYear(String name) {
        try {
            String[] titleInfo = name.split(", ");
            String lastSubstr = titleInfo[titleInfo.length - 1];
            // Check the last substring for year format matching
            if (lastSubstr.matches("\\d{4}")) {
                return Integer.parseInt(lastSubstr);
            } else {
                log.warn("product's crop year is not specified");
                return null;
            }
        } catch (NullPointerException ex) {
            log.warn("product's crop year is not specified");
            return null;
        }
    }

    /**
     * Парсер объема.
     * @param el Контейнер, в котором лежит объем вина.
     * @return Объем в мл ИЛИ null, если его нет.
     */
    private Double parseVolume(Element el) {
        try {
            Element volumeElement = el.selectFirst("label");
            String volumeValue = volumeElement.ownText();
            volumeValue = volumeValue.replaceAll("\\s[мл]+", "");

            double volume = Double.parseDouble(volumeValue);
            volume = (volume%1 == 0) ? volume / 1000 : volume;

            return volume;
        } catch (NullPointerException ex) {
            log.warn("product's volume is not specified");
            return null;
        }
    }

    /**
     * Парсер свойств: страна и регион.
     * @param el Контейнер, в котором лежит описание происхождения вина.
     * @return  Свойства: страна и регион в виде массива из двух элементов, которые мы достали, ИЛИ массив из двух Null, если таковых нет.
     */
    private String[] parseCountryAndRegions(Element el){
        try {
            Element countryElement = el.selectFirst("span:contains(Рег)").nextElementSibling();
            String country = countryElement.text();

            Element regionElement = countryElement.nextElementSibling();
            String allRegions = regionElement.text();

            //add region names to the result string as long as there are elements containing them
            String nextRegion;
            Element nextElement;
            while (regionElement.nextElementSibling() != null) {
                nextElement = regionElement.nextElementSibling();
                nextRegion = nextElement.text();
                allRegions = String.join(", ", new String[]{allRegions, nextRegion});
                regionElement = nextElement;
            }

            return new String[] {country, allRegions};
        } catch (NullPointerException ex) {
            log.warn("product's country and region are not specified");
            return new String[] {null,null};
        }
    }

    /**
     * Парсер производителя вина.
     * @param el Контейнер, в котором лежит производитель вина.
     * @return Производитель ИЛИ null, если его нет.
     */
    private String parseManufacturer(Element el) {
        try {
            Element manufacturerElement = el.selectFirst("span:contains(Прои)").nextElementSibling();
            return manufacturerElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's manufacturer is not specified");
            return null;
        }
    }

    /**
     * Парсер бренда вина.
     * @param el Контейнер, в котором лежит бренд вина.
     * @return Бренд ИЛИ null, если его нет.
     */
    private String parseBrand(Element el) {
        try {
            Element brandElement = el.selectFirst("span:contains(Брен)").nextElementSibling();
            return brandElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's brand is not specified");
            return null;
        }
    }

    /**
     * Парсер крепости вина.
     * @param el Контейнер, в котором лежит описание крепости вина.
     * @return Крепость ИЛИ null, если совйства нет.
     */
    private String parseStrength(Element el) {
        try {
            Element strengthElement = el.selectFirst("span:contains(Креп)").nextElementSibling();
            return strengthElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's strength is not specified");
            return null;
        }
    }

    /**
     * Парсер сорта винограда.
     * @param el Контейнер, в котором лежит описание сорта винограда.
     * @return Объединенная строка сортов винограда ИЛИ null, если их нет.
     */
    private String parseGrape(Element el) {
        try {
            Element grapeElement = el.selectFirst("span:contains(Сорт)").nextElementSibling();
            String allGrape = grapeElement.text();

            //add grape sorts to the result string as long as there are elements containing them
            String nextGrape;
            Element nextElement;
            while (grapeElement.nextElementSibling() != null) {
                nextElement = grapeElement.nextElementSibling();
                nextGrape = nextElement.text();
                allGrape = String.join(", ", new String[]{allGrape, nextGrape});
                grapeElement = nextElement;
            }

            return grapeElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's grape sort is not specified");
            return null;
        }
    }

    /**
     * Парсер свойств: оттенок и сладость/сухость.
     * @param el Контейнер, в котором лежит описание свойств вина.
     * @return Свойства: оттенок и сладость/сухость в виде массива из дыух элементов, которые мы достали, ИЛИ массив из двух Null, если таковых нет.
     */
    private String[] parseColorAndSugar(Element el) {
        try {
            Element colorElement = el.selectFirst("span:contains(Вино:)").nextElementSibling();
            Element sugarElement = colorElement.nextElementSibling();
            return new String[] {colorElement.text(),sugarElement.text()};
        } catch (NullPointerException ex) {
            log.warn("product's color and sugar are not specified");
            return new String[] {null, null};
        }
    }

    /**
     * Парсер картинки.
     * @param el Контейнер, в котором лежит ссылка на картинку.
     * @return Ссылка на картинку, которую мы достали, ИЛИ Null, если картинки нет.
     */
    private String parseImageUrl(Element el) {
        try {
            Element imageElement = el.selectFirst("a.img-container");
            return imageElement.attr("href");
        } catch (NullPointerException ex) {
            log.warn("product's image is not specified");
            return null;
        }
    }

    /**
     * Парсер описания.
     * @param el Контейнер, в котором лежит описание.
     * @return Описание, которое мы достали, ИЛИ Null, если описания нет.
     */
    private String parseDescription(Element el) {
        try {
            Element descriptionElement = el.selectFirst(".description-block");
            return descriptionElement.text();
        } catch (NullPointerException ex) {
            log.warn("product's description is not specified");
            return null;
        }
    }

    /**
     * Загрузка изображения по ссылке
     * @param imageUrl Строка, ссылка на изображение
     * @return Сущность с blob полем, в котором хранится изображение или Null
     */
    private ImageAlcohol downloadImageByUrl(String imageUrl){
        try {
            URL url = new URL(imageUrl);
            ImageAlcohol imageWine = new ImageAlcohol();
            BufferedImage bufferedImage = ImageIO.read(url.openStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "jpg", baos);
            Blob blobImage = new SerialBlob(baos.toByteArray());
            imageWine.setImage(blobImage);
            return imageWine;
        } catch (IOException ex){
            log.warn("Cannot get image from link: {}", imageUrl, ex);
            return null;
        } catch (SQLException ex) {
            log.warn("Cannot convert image to blob from link: {}", imageUrl, ex);
            return null;
        }
    }

    /**
     * Добавляет изображение для строк в бд, которые не имеею изображение
     * @param imageUrl ссылка на изображение
     * @param urlToProductPage ссылка на продукт
     */
    private void updateImageByProductUrl(String imageUrl, String urlToProductPage){
        ImageAlcohol imageAlcohol = downloadImageByUrl(imageUrl);
        wineService.updateImage(imageAlcohol, urlToProductPage);
    }
}
