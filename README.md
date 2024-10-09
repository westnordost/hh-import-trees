# hh-import-trees

Kotlin-Applikation um Hamburgs [Straßenbaumkataster](https://metaver.de/trefferanzeige?docuuid=C1C61928-C602-4E37-AF31-2D23901E2540)
in OpenStreetMap zu importieren.

### Nutzung

```shell
java -jar hh-import-trees-0.2.jar \
  <aktuelles Straßenbaumkataster> \
  [<Straßenbaumkataster des letzten Imports> \
   <Timestamp an dem der letzte Import abgeschlossen war>
  ]
```