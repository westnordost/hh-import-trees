# hh-import-trees

Kotlin-Applikation um Hamburgs [Straßenbaumkataster](https://metaver.de/trefferanzeige?docuuid=C1C61928-C602-4E37-AF31-2D23901E2540)
in OpenStreetMap zu importieren.

Zurzeit herunterladbar als GML via https://geodienste.hamburg.de/HH_WFS_Strassenbaumkataster?SERVICE=WFS&VERSION=1.1.0&REQUEST=GetFeature&typename=de.hh.up:strassenbaumkataster
Dies scheint (leider) ein rolling release zu sein.

### Nutzung

```shell
java -jar hh-import-trees-0.4.jar \
  <aktuelles Straßenbaumkataster> \
  [<Straßenbaumkataster des letzten Imports> \
   <Timestamp an dem der letzte Import abgeschlossen war>
  ]
```