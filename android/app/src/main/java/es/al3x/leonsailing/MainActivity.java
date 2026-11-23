package es.al3x.leonsailing;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BoatNet.class);
        super.onCreate(savedInstanceState);
        // Pantalla siempre encendida durante la regata
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Ignorar el tamaño de letra del sistema: las cifras ya se ajustan solas a cada casilla
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().getSettings().setTextZoom(100);
        }
    }
}
