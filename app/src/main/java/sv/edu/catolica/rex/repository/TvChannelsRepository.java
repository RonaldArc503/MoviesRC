package sv.edu.catolica.rex.repository;

import java.util.ArrayList;
import java.util.List;
import sv.edu.catolica.rex.models.TvChannel;

/**
 * Repositorio de canales de televisión en vivo.
 * Contiene únicamente señales verificadas activas con fuentes primarias y de respaldo.
 */
public class TvChannelsRepository {

    public static final String CATEGORY_ALL = "Todos";
    public static final String CATEGORY_NACIONALES = "Nacionales SV";
    public static final String CATEGORY_NOTICIAS = "Noticias";
    public static final String CATEGORY_MOVIES = "Películas & Series";
    public static final String CATEGORY_RELIGION = "Religión & Fe";
    public static final String CATEGORY_INTERNACIONAL = "Internacionales";

    private static final List<TvChannel> CHANNELS = new ArrayList<>();

    static {
        initChannels(CHANNELS);
    }

    private static void initChannels(List<TvChannel> list) {
        // =========================================================================
        // NACIONALES EL SALVADOR (Canales verificados en vivo)
        // =========================================================================
        list.add(new TvChannel("canal-4-sv", "Canal 4", CATEGORY_NACIONALES,
            "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1b/Canal_4_TCS.png/960px-Canal_4_TCS.png",
            "http://186.33.26.18:8000/play/a0qk/index.m3u8",
            "http://45.167.2.101:8000/play/a0qk/index.m3u8",
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("canal-2-sv", "Canal 2", CATEGORY_NACIONALES,
            "https://upload.wikimedia.org/wikipedia/commons/thumb/7/79/Canal_2_TCS.png/960px-Canal_2_TCS.png",
            "http://186.33.26.18:8000/play/a0qp/index.m3u8",
            "http://45.167.2.101:8000/play/a0qp/index.m3u8",
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("canal-6-sv", "Canal 6", CATEGORY_NACIONALES,
            "https://i.imgur.com/M4muPLK.png",
            "http://45.167.2.101:8000/play/a0qs/index.m3u8",
            "http://186.33.26.18:8000/play/a0qs/index.m3u8",
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("canal-12-sv", "Canal 12", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/canal-12-sv.webp",
            "http://186.33.26.18:8000/play/a0nw/index.m3u8",
            "http://45.167.2.101:8000/play/a0nw/index.m3u8",
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("canal-10-sv", "Canal 10 TVES", CATEGORY_NACIONALES,
            "https://yt3.googleusercontent.com/463ZErwFd6NkeWDpIew5J5DCHsFe3_jnN4Z7a923LfPOFSSYXaU8qC4yz3-XkLLJMYBx7j-rKA=s900-c-k-c0x00ffffff-no-rj",
            "https://www.youtube-nocookie.com/embed/live_stream?channel=UCsOiRor7ATAU3NCTAFehOYw&autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "SV"));

        list.add(new TvChannel("megavisin-canal-21-sv", "Megavisión Canal 21", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/canal-21-sv.webp",
            "https://mgvchannel21-ioriver-cdn.encoders.immergo.tv/0/streamPlaylist.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("canal-11-tutv-sv", "Canal 11 TuTV", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/canal-11-sv.webp",
            "https://stream.giostreaming.app:19360/canal11/canal11.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("canal-3-impresionante-sv", "Canal 3 Impresionante", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/canal-3-impresionante-sv.webp",
            "https://cloud2.streaminglivehd.com:19360/8036/8036.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("rtv-canal-57-sv", "RTV Canal 57", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/rtv-canal-57-sv.webp",
            "https://stream.giostreaming.app/rtvcanal57/rtvcanal57.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("rtv-catlica-canal-40-sv", "RTV Católica Canal 40", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/rtv-catlica-canal-40-sv.webp",
            "https://stream.giostreaming.app/canal40/canal40.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("tabertv-sv", "TaberTV", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/tabertv-sv.webp",
            "https://5fc584f3f19c9.streamlock.net/tabertv/videotabertv/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("el-camino-tv-sv", "El Camino TV", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/el-camino-tv-sv.webp",
            "https://5d32e2b9b7eed.streamlock.net:4443/ectv/ectv/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("josu-tv-sv", "Josué TV", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/josu-tv-sv.webp",
            "https://videoserver.tmcreativos.com:19360/abbftuhxxs/abbftuhxxs.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("tv-getsemani-sv", "TV Getsemaní", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/tv-getsemani-sv.webp",
            "https://serversv.com:8080/hls/tvgetsemani.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        list.add(new TvChannel("tv-leon-de-juda-sv", "TV León de Judá", CATEGORY_NACIONALES,
            "https://assets.teleon.tv/180_webp/tv-leon-de-juda-sv.webp",
            "https://6110f70ea8d0e.streamlock.net/1838/1838/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "SV"));

        // =========================================================================
        // NOTICIAS E INFORMACIÓN
        // =========================================================================
        list.add(new TvChannel("cnn-en-espanol-sv", "CNN en Español", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/cnn-en-espanol-ec.webp",
            "https://amg00803-cnnfast-amg00803c3-lg-es-5477.playouts.now.amagi.tv/playlist/amg00803-cnnfastinternational-cnnesponal-lges/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "INT"));

        list.add(new TvChannel("las-estrellas-sv", "Las Estrellas", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/las-estrellas-co.webp",
            "https://channel01-onlymex.akamaized.net/hls/live/2022749/event01/index.m3u8",
            null,
            TvChannel.TYPE_HLS, "MX"));

        list.add(new TvChannel("telemundo-pr", "Telemundo Puerto Rico", CATEGORY_NOTICIAS,
            "https://www.brandemia.org/wp-content/uploads/2012/06/logo_principal_telemundo.jpg",
            "https://nbculocallive.akamaized.net/hls/live/2037499/puertorico/stream1/master.m3u8",
            null,
            TvChannel.TYPE_HLS, "PR"));

        list.add(new TvChannel("dw-espanol-de", "DW Español", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/dw-tv-de.webp",
            "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8",
            null,
            TvChannel.TYPE_HLS, "DE"));

        list.add(new TvChannel("estrella-tv-mx", "Estrella TV", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/estrella-tv-mx.webp",
            "https://estrellatv-oando.amagi.tv/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "US"));

        list.add(new TvChannel("estrella-news-mx", "Estrella News", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/estrella-news-mx.webp",
            "https://estrella-news-oando.amagi.tv/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "US"));

        list.add(new TvChannel("telesur-ve", "Telesur", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/telesur-ve.webp",
            "https://mblesmain01.telesur.ultrabase.net/mbliveMain/480p/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "VE"));

        list.add(new TvChannel("al-jazeera-english-qa", "Al Jazeera English", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/al-jazeera-english-qa.webp",
            "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8",
            null,
            TvChannel.TYPE_HLS, "QA"));

        list.add(new TvChannel("france-24-espanol-fr", "France 24 Español", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/france-24-espanol-fr.webp",
            "https://www.youtube-nocookie.com/embed/live_stream?channel=UCUdOoVWuWmgo1wByzcsyKDQ&autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "FR"));

        list.add(new TvChannel("euronews-espanol-fr", "EuroNews Español", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/euronews-espanol-fr.webp",
            "https://www.youtube-nocookie.com/embed/O9mOtdZ-nSk?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "EU"));

        list.add(new TvChannel("telediario-por-canal-6-mx", "Telediario Canal 6", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/telediario-mx.webp",
            "https://www.youtube-nocookie.com/embed/live_stream?channel=UCKuAmo9OGf8kw2jrPWCpHdA&autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "MX"));

        list.add(new TvChannel("telefe-noticias-ar", "Telefe Noticias", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/telefe-noticias-ar.webp",
            "https://www.youtube-nocookie.com/embed/XhAYcYpPzTc?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "AR"));

        list.add(new TvChannel("telemundo-noticias-us", "Telemundo Noticias", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/telemundo-noticias-us.webp",
            "https://www.youtube-nocookie.com/embed/c660thEPeeY?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "US"));

        list.add(new TvChannel("univision-noticias-us", "Univision Noticias", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/univision-noticias-us.webp",
            "https://www.youtube-nocookie.com/embed/V4C7VNfRATA?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "US"));

        list.add(new TvChannel("24-horas-internacional-es", "24 horas Internacional", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/24-horas-es.webp",
            "https://www.youtube-nocookie.com/embed/b4tE5aKhtlg?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "ES"));

        list.add(new TvChannel("bloomberg-business-us", "Bloomberg Business", CATEGORY_NOTICIAS,
            "https://assets.teleon.tv/180_webp/bloomberg-us.webp",
            "https://www.youtube-nocookie.com/embed/QB5BNdBFujE?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "US"));

        // =========================================================================
        // PELÍCULAS Y SERIES (24/7 FAST CHANNELS)
        // =========================================================================
        list.add(new TvChannel("sony-one-cinema-mx", "Sony One Cinema", CATEGORY_MOVIES,
            "https://assets.teleon.tv/180_webp/sony-one-cinema-mx.webp",
            "https://55a8e146e2a14a0288735149357b44c0.mediatailor.us-east-1.amazonaws.com/v1/master/44f73ba4d03e9607dcd9bebdcb8494d86964f1d8/LG-mx_SonyOneCinema/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "MX"));

        list.add(new TvChannel("sony-one-novelas-mx", "Sony One Novelas", CATEGORY_MOVIES,
            "https://assets.teleon.tv/180_webp/sony-one-novelas-mx.webp",
            "https://8d4a17fe54c04f04bb010a8d291c7d41.mediatailor.us-east-1.amazonaws.com/v1/master/44f73ba4d03e9607dcd9bebdcb8494d86964f1d8/LG-mx_SonyOneNovelas/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "MX"));

        list.add(new TvChannel("runtime-accion-mx", "Runtime Acción", CATEGORY_MOVIES,
            "https://assets.teleon.tv/180_webp/runtime-accion-mx.webp",
            "https://stream.ads.ottera.tv/playlist.m3u8?network_id=13232&avod=1&coppa=0",
            null,
            TvChannel.TYPE_HLS, "MX"));

        list.add(new TvChannel("runtime-crimen-mx", "Runtime Crimen", CATEGORY_MOVIES,
            "https://assets.teleon.tv/180_webp/runtime-crimen-mx.webp",
            "https://stream.ads.ottera.tv/playlist.m3u8?network_id=11825&avod=1&coppa=0",
            null,
            TvChannel.TYPE_HLS, "MX"));

        list.add(new TvChannel("runtime-familia-mx", "Runtime Familia", CATEGORY_MOVIES,
            "https://assets.teleon.tv/180_webp/runtime-familia-mx.webp",
            "https://stream.ads.ottera.tv/playlist.m3u8?network_id=13228&avod=1&coppa=0",
            null,
            TvChannel.TYPE_HLS, "MX"));

        list.add(new TvChannel("runtime-romance-mx", "Runtime Romance", CATEGORY_MOVIES,
            "https://assets.teleon.tv/180_webp/runtime-romance-mx.webp",
            "https://stream.ads.ottera.tv/playlist.m3u8?network_id=11833&avod=1&coppa=0",
            null,
            TvChannel.TYPE_HLS, "MX"));

        list.add(new TvChannel("masha-y-el-oso-mx", "Masha y el Oso", CATEGORY_MOVIES,
            "https://assets.teleon.tv/180_webp/masha-y-el-oso-mx.webp",
            "https://www.youtube-nocookie.com/embed/8ZTe5n_SFfg?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "INT"));

        // =========================================================================
        // RELIGIÓN Y FE
        // =========================================================================
        list.add(new TvChannel("3abn-latino-us", "3ABN Latino", CATEGORY_RELIGION,
            "https://assets.teleon.tv/180_webp/3abn-latino.webp",
            "https://3abn.bozztv.com/3abn2/Lat_live/smil:Lat_live.smil/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "INT"));

        list.add(new TvChannel("enlace-tbn-us", "Enlace TBN", CATEGORY_RELIGION,
            "https://assets.teleon.tv/180_webp/enlace-tbn-us.webp",
            "https://livecdn.enlace.plus/enlace/smil:enlace-hd.smil/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "INT"));

        list.add(new TvChannel("el-sembrador-tv-cl", "El Sembrador TV", CATEGORY_RELIGION,
            "https://assets.teleon.tv/180_webp/el-sembrador-tv-cl.webp",
            "https://tv.arkeo.cl:1936/elsembrador/elsembrador/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "CL"));

        list.add(new TvChannel("mara-vision-us", "María Visión", CATEGORY_RELIGION,
            "https://assets.teleon.tv/180_webp/mara-vision-us.webp",
            "https://1601580044.rsc.cdn77.org/live/_jcn_/amls:PALMIAMI/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "INT"));

        list.add(new TvChannel("tv-mana-espanol-pt", "TV Maná Español", CATEGORY_RELIGION,
            "https://assets.teleon.tv/180_webp/tv-mana-espanol-pt.webp",
            "https://w1.manasat.com/tvmana-espanol/smil:tvmana-espanol.smil/playlist.m3u8",
            null,
            TvChannel.TYPE_HLS, "PT"));

        // =========================================================================
        // INTERNACIONALES
        // =========================================================================
        list.add(new TvChannel("canal-10-cr", "Canal 10 Costa Rica", CATEGORY_INTERNACIONAL,
            "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEifrYu2VhqPlQPQxzW-R2AY6Ab86FqjoUQLhc0ohWqKDTivR8b50cI-GEV3ronDrPlJD6i2AYf4ElymF2_PE0B-acRpuz9lFF8f7VFWKeIZWfvBrrrncC3ORGSz2bRHwy-Q_5HR2_SDEUyNAyKuJgYw-X57gOeF6K5SMXemEoCYvm2Sh9gwL6tuw7JQh_k/s400/14.png",
            "https://acceso.mediosdecostarica.com:3606/hybrid/play.m3u8",
            null,
            TvChannel.TYPE_HLS, "CR"));

        list.add(new TvChannel("america-tv-internacional-ar", "América TV Internacional", CATEGORY_INTERNACIONAL,
            "https://assets.teleon.tv/180_webp/america-tv-internacional-ar.webp",
            "https://www.youtube-nocookie.com/embed/zcWXboTnous?autoplay=1",
            null,
            TvChannel.TYPE_YOUTUBE, "AR"));

        list.add(new TvChannel("tv-brics-espanol-ru", "TV BRICS Español", CATEGORY_INTERNACIONAL,
            "https://assets.teleon.tv/180_webp/tv-brics-ru.webp",
            "https://espbrics.mediacdn.ru/cdn/brics/spanish/tracks-v1a1/mono.ts.m3u8",
            null,
            TvChannel.TYPE_HLS, "RU"));
    }

    public static List<TvChannel> getAllChannels() {
        return new ArrayList<>(CHANNELS);
    }

    public static List<TvChannel> getChannelsByCategory(String category) {
        if (category == null || category.trim().isEmpty() || CATEGORY_ALL.equalsIgnoreCase(category)) {
            return getAllChannels();
        }
        List<TvChannel> filtered = new ArrayList<>();
        for (TvChannel channel : CHANNELS) {
            if (category.equalsIgnoreCase(channel.getCategory())) {
                filtered.add(channel);
            }
        }
        return filtered;
    }

    public static List<String> getAvailableCategories() {
        List<String> categories = new ArrayList<>();
        categories.add(CATEGORY_ALL);
        categories.add(CATEGORY_NACIONALES);
        categories.add(CATEGORY_NOTICIAS);
        categories.add(CATEGORY_MOVIES);
        categories.add(CATEGORY_RELIGION);
        categories.add(CATEGORY_INTERNACIONAL);
        return categories;
    }
}
