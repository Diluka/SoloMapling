package soloMapling.beidou;

import org.gms.provider.wz.XMLDomMapleData;
import org.gms.server.ItemInformationProvider;
import org.gms.util.Pair;
import org.w3c.dom.Document;

/**
 * A DOM Document is shared by many XMLDomMapleData wrappers. The official
 * per-wrapper synchronized methods do not protect Xerces' shared NodeList cache.
 * This boundary applies equally to native player threads and Solo virtual threads.
 */
public privileged aspect WzDomConcurrency {
    Object around(XMLDomMapleData data):
            execution(public * XMLDomMapleData.*(..)) && this(data) {
        Document document = data.node.getOwnerDocument();
        Object monitor = document == null ? data.node : document;
        synchronized (monitor) {
            return proceed(data);
        }
    }

    // Keep the host's one existing name cache, protecting its HashMap's complete
    // lookup/load/publish operation instead of adding a second Solo-only cache.
    Pair around(ItemInformationProvider provider, int itemId):
            execution(Pair ItemInformationProvider.getNameDesc(int))
            && this(provider) && args(itemId) {
        synchronized (provider) {
            return proceed(provider, itemId);
        }
    }
}
