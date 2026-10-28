package soloMapling.beidou;

import org.gms.provider.Data;
import org.gms.provider.DataDirectoryEntry;
import org.gms.provider.DataFileEntry;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.wz.DataType;
import org.gms.provider.wz.WZFiles;
import org.gms.server.ItemInformationProvider;
import org.gms.util.DatabaseConnection;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.mockito.Mockito.*;

/** One deterministic singleton bootstrap, shared by native inventory and trade tests. */
public final class InventoryMetadataTestContext {
    private static boolean installed;
    private InventoryMetadataTestContext() { }

    public static synchronized void install() throws Exception {
        if (installed) return;
        CharacterTestContext.install();
        DataProvider provider = itemMetadata();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        // The real singleton reads card metadata at construction; no JDBC or WZ input escapes these seams.
        try (MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class);
             MockedStatic<DataProviderFactory> data = mockStatic(DataProviderFactory.class)) {
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            data.when(() -> DataProviderFactory.getDataProvider(any(WZFiles.class))).thenReturn(provider);
            ItemInformationProvider.getInstance();
        }
        installed = true;
    }

    private static DataProvider itemMetadata() {
        DataProvider provider = mock(DataProvider.class);
        DataDirectoryEntry root = mock(DataDirectoryEntry.class), consume = mock(DataDirectoryEntry.class);
        DataFileEntry file = mock(DataFileEntry.class);
        when(root.getSubdirectories()).thenReturn(List.of(consume));
        when(consume.getName()).thenReturn("Consume"); when(consume.getFiles()).thenReturn(List.of(file));
        when(file.getName()).thenReturn("0200.img"); when(provider.getRoot()).thenReturn(root);
        Data empty = mock(Data.class);
        when(provider.getData(anyString())).thenReturn(empty);
        Data container = mock(Data.class); when(provider.getData("Consume/0200.img")).thenReturn(container);
        Data blocked = itemInfo(true), sale = itemInfo(false), other = itemInfo(false);
        when(container.getChildByPath("02000991")).thenReturn(blocked);
        when(container.getChildByPath("02000992")).thenReturn(sale);
        when(container.getChildByPath("02000993")).thenReturn(other);
        return provider;
    }

    private static Data itemInfo(boolean blocked) {
        Data item = mock(Data.class);
        Data tradeBlock = integer(blocked ? 1 : 0), slotMax = integer(100);
        when(item.getChildByPath("info/tradeBlock")).thenReturn(tradeBlock);
        when(item.getChildByPath("info/slotMax")).thenReturn(slotMax);
        return item;
    }
    private static Data integer(int value) {
        Data data = mock(Data.class); when(data.getType()).thenReturn(DataType.INT); when(data.getData()).thenReturn(value); return data;
    }
}
