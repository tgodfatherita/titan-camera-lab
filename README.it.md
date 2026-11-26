# Titan Camera Lab 0.16

La versione pubblica per Titan 2 è disponibile nella [release GitHub](https://github.com/tgodfatherita/titan-camera-lab/releases/tag/v0.16). Sul telefono scaricare e aprire **TitanCameraLab-0.16-arm64.apk**. La stessa pagina contiene il codice e l’archivio dei sorgenti delle librerie incluse.

[☕ Offrimi un caffè](https://buymeacoffee.com/tgodfather)

La fotocamera salva in galleria un video stabilizzato per ogni nuova ripresa. Con **Giroscopio · prova** il Titan 2 applica alla ripresa la correzione scelta nel confronto del 23 novembre. La GPU ridisegna i fotogrammi e i codec hardware decodificano e ricomprimono il video. L'audio AAC conserva i dati e i tempi della registrazione. **Marcata** usa il rilevamento del movimento nell'immagine di FFmpeg/libvidstab e, dove disponibile, la codifica hardware.

Prototipo Android nativo Camera2 per Unihertz Titan 2, sviluppato da Carmine Petruziello. Pacchetto `it.carmine.titancamera`. In fondo alle **Impostazioni** si trovano la versione installata e il nome dell’autore.

- Le due frecce in basso a destra cambiano fotocamera in **Foto** e **Video**. **−**, **+** e il gesto con due dita regolano lo zoom. Toccare il valore per tornare a **1×**. Sul Titan la posteriore arriva a **10×** e la frontale a **4×**. L’ingrandimento passa da Foto a Video e resta fisso durante la ripresa.
- La barra con il sole resta nella fascia nera a destra in **Foto** e **Video**. Trascinare il sole verso l’alto per schiarire o verso il basso per scurire. A **0 EV** la fotocamera usa la luminosità automatica. Il Titan offre nove livelli tra **−2 e +2 EV**. La regolazione è disponibile anche durante una ripresa. L’app ricorda un valore per ciascuna modalità e fotocamera.
- In **Foto**, premere la **barra spaziatrice** o tenere premuto il **pulsante di scatto**. Dal pulsante si aprono le durate **2, 5 e 10 secondi**. Toccare una durata per iniziare subito il conto alla rovescia. Alla fine l’app salva una foto. La **×** annulla e un tocco breve sul pulsante scatta subito.
- Il fulmine accanto all’ingranaggio controlla il LED della fotocamera posteriore. In **Foto**, il fulmine giallo attiva il lampo allo scatto. La fotocamera misura l’esposizione con un prelampo e salva una sola foto con tutti e tre i profili. In **Video**, lo stesso tasto accende e spegne la luce continua anche durante la registrazione. L’app ricorda separatamente flash foto e torcia video. Alla prima apertura entrambi sono spenti.
- Alla riapertura l’app ripristina fotocamera, modalità, profilo foto e stabilizzazione. Ciascuna fotocamera ricorda il proprio zoom e i propri formati foto e video.
- La miniatura in basso a sinistra mostra e apre l’ultima foto scattata con l’app, anche dopo aver registrato un video. Se quella foto viene eliminata, alla riapertura compare la foto precedente disponibile. Dall’ingranaggio in alto a destra si accede a **Impostazioni**, dove si può aprire anche l’ultimo video stabilizzato.
- Toccare il formato in alto a sinistra oppure aprire **Impostazioni → Formati di acquisizione**. Selezionare il rapporto **1:1**, **4:3** o **16:9**, scegliere la risoluzione e premere **Applica**. Le scelte per foto e video vengono ricordate separatamente. Le dimensioni in pixel nel selettore seguono l’orientamento verticale del file visualizzato.
- Per le foto l’app consulta anche le dimensioni JPEG ad alta risoluzione. Sul Titan 2 il formato 4:3 arriva a **50,3 MP** sulla posteriore (6144 × 8192 in verticale) e a **8,1 MP** sulla frontale (2460 × 3280). I formati video provengono dalla fotocamera selezionata e dai codec H.264 a **30 fps**, con audio AAC. Alla prima apertura la posteriore usa 1:1 a 9 MP per le foto, la frontale 4:3 a 8,1 MP. Entrambe partono da Full HD per i video.
- **Registra** e **Ferma e salva** avviano la registrazione e il salvataggio automatico. La ripresa e le misure del giroscopio restano in una cartella privata fino alla verifica del risultato. L'app pubblica il video finale e libera i file di lavoro.
- **Scegli video** importa un filmato esistente tramite il selettore Android. Il file selezionato appartiene all'utente. Giroscopio può rielaborare anche registrazioni della versione 0.6 o 0.7 con il relativo archivio movimento. Marcata può elaborare filmati privi di dati del giroscopio.
- **Annulla** sospende il salvataggio. **Riprendi salvataggio** elabora di nuovo la ripresa privata, anche dopo la riapertura dell'app. **Scarta ripresa in sospeso** richiede conferma. **Apri video stabilizzato** apre l'ultimo risultato.
- Ogni risultato viene verificato prima della pubblicazione controllando dimensioni, fotogrammi, campioni audio e tempi. Il salvataggio tiene traccia dell'elemento in attesa per recuperare le interruzioni tra pubblicazione e pulizia.
- Il profilo Giroscopio usa la calibrazione del Titan 2 verticale a 30 fps. Adatta la proiezione al rapporto, alla risoluzione e al ritaglio dello zoom. In anteprima viene mostrato il margine fisso del profilo. La correzione si applica dopo la registrazione.
- Con il flash spento, **Bilanciata** usa l’esposizione automatica del Titan. Quando il tempo di posa supera circa 1/90 s o il fuoco è incerto, confronta tre scatti e salva in galleria il JPEG con più dettaglio sul soggetto. In buona luce, con fuoco confermato, esegue uno scatto singolo. **Automatica** esegue sempre uno scatto singolo. **Rapida** limita il tempo a circa 1/120 s per i soggetti in movimento e in interni può produrre un risultato più scuro e rumoroso.
- Sulla posteriore, toccare un dettaglio per scegliere il soggetto. L’app verifica il contrasto nel punto scelto a diverse posizioni della lente e mantiene il fuoco per lo scatto. Il riquadro passa dal giallo al verde alla conferma. In caso di riquadro rosso, toccare un bordo o una scritta. Premendo **Scatta** durante la ricerca, l’app attende la fine della messa a fuoco.
- La frontale del Titan ha il fuoco fisso. Toccando il mirino in modalità Foto si sceglie dove misurare l’esposizione. L’anteprima è a specchio e i file salvati conservano il verso naturale della scena.
- Salvataggio locale in `DCIM/TitanCameraLab` tramite MediaStore. Nessun permesso Internet.
- Uscire dall’app durante una registrazione la termina e avvia il salvataggio. Se Android rinvia l’avvio in background, l'app riprende alla riapertura.
- L’orientamento dell’app è verticale. Le foto mantengono i dati e la risoluzione del JPEG prodotto dal telefono.

## Compilazione

Su Windows impostare `JAVA_HOME` sulla cartella del JDK 17 e `ANDROID_HOME` sull’Android SDK con Platform 35 e Build Tools 35.0.0. Eseguire `setup-video-dependencies.ps1` e poi `build.ps1` per ottenere un APK di prova arm64-v8a. L’opzione `-ReleaseKeystore` consente di firmare una build con una propria chiave. [Istruzioni complete](README.md#build-on-windows) · [Licenze e sorgenti delle librerie](THIRD-PARTY.md)

## Prova sul telefono

1. Registrare una breve camminata e premere **Ferma e salva**.
2. Aprire il risultato in galleria. Sul video di riferimento da 22,15 s Giroscopio ha richiesto 6,4–6,9 s sul Titan 2, rispetto a 80,9 s con la versione 0.7. Marcata è passata da 158,5 a 108,2 s. La misura include la verifica e il salvataggio.
3. Selezionare **Bilanciata** in **Impostazioni**. In **Foto**, toccare una scritta nell’anteprima e premere **Scatta**. Per verificare il cambio di fuoco, toccare prima lo sfondo e poi la scritta. Per confrontare la luminosità in interni, ripetere la foto in **Rapida** dalla stessa posizione.
4. Provare stop immediato, uscita dall'app, riapertura e cambio modalità.

Con la versione 0.12 sono stati verificati sul Titan i JPEG quadrati da 3504 × 3504, panoramici da 2304 × 4096 e in 4:3 da 6144 × 8192. A 50,3 MP due scatti Bilanciata a 1× e 2× hanno dato un ingrandimento relativo di 1,999× misurato sui dettagli della scena. Sono stati verificati file H.264 con audio AAC in formato quadrato, 4:3 e 4K 16:9. In 4K Giroscopio ha elaborato 106 fotogrammi in 2,8 s e Marcata 75 fotogrammi in 49,3 s.

La diagnosi iniziale dell'app originale ha rilevato EIS OFF durante anteprima e registrazione. Il dispositivo dichiara EIS disponibile, OIS non disponibile e controllo manuale del sensore. La qualità visiva va ancora provata su scene e condizioni di luce diverse. Il profilo mobile ha gli stessi parametri del confronto scelto, ma una diversa versione del motore e dell'architettura può produrre risultati leggermente diversi.

Sul filmato di riferimento la prova accelerata ha conservato tutti i 663 fotogrammi, gli stessi tempi di presentazione e i 1038 pacchetti AAC identici. Su 40 fotogrammi distribuiti lungo la ripresa, la somiglianza media (SSIM a 270×480) con la versione 0.7 è stata 0,995. Il file accelerato a 16 Mbit/s occupava 44,7 MB. Sono stati verificati sul Titan anche il salvataggio di una sola registrazione visibile, l’uscita con Home e la ripresa del salvataggio dopo annullamento e riavvio dell’app.

## Verifica della versione 0.13 sul Titan

- La frontale ha salvato JPEG da 2460 × 3280 a 1× e 1440 × 2560 a 2×. Con la frontale appoggiata sono stati verificati video H.264 con audio AAC in Full HD con Giroscopio (105 fotogrammi) e in 4:3 con Marcata (115 fotogrammi). Il confronto con la foto ha confermato l’orientamento dei due video. La qualità durante la camminata con la frontale resta da valutare.
- Dopo la chiusura dell’app e il riavvio del processo, il mirino è tornato su Video, frontale, 2×, 4:3. In Impostazioni erano ancora selezionati Rapida, Marcata e il formato foto 16:9. Il ritorno alla posteriore ha ripristinato il suo zoom e i suoi formati.
- La miniatura è stata confrontata con due scatti consecutivi. Mostrava il secondo e al tocco Android riceveva lo stesso JPEG, anche dopo il riavvio e la pubblicazione di entrambi i video.
- L’icona adattiva di Titan Camera Lab raffigura un obiettivo scuro con un anello giallo. Include la versione monocromatica per le icone a tema di Android.

## Verifica della versione 0.14 sul Titan

- Due JPEG da 3504 × 3504 dello stesso soggetto riportano nei dati EXIF il flash spento e il lampo scattato secondo la scelta nel mirino. Il lampo è stato verificato anche in Automatica e in Rapida a 50,3 MP con messa a fuoco sul punto.
- Durante una registrazione 4K l’operatore ha acceso e spento la torcia. La fotocamera ha confermato entrambe le transizioni e Giroscopio ha salvato in galleria un video stabilizzato di 8,7 secondi con 258 fotogrammi.

## Verifica della versione 0.15 sul Titan

- Con la posteriore ferma, tre JPEG da 3504 × 3504 a −2, 0 e +2 EV hanno restituito una luminosità media di 54, 124 e 196 su 255 nella stessa area del soggetto. Nell’anteprima Video la stessa prova ai due estremi ha dato 52 e 183 su 255.
- In Foto e Video la barra occupa la stessa posizione nella fascia nera. Il ritorno da Video a Foto ha ripristinato il valore impostato per le foto.
- I conti da 2, 5 e 10 secondi hanno salvato un JPEG ciascuno. Il tasto Indietro durante il conto ha lasciato aperta la fotocamera e invariato il numero di scatti in galleria.

