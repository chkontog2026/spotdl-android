# SpotDL Android

Αυτόνομη Android εφαρμογή για αντιστοίχιση Spotify track/album links με δημόσια διαθέσιμες πηγές ήχου και αποθήκευση MP3 στη συσκευή.

## Ενημερώσεις εφαρμογής

Η εφαρμογή ελέγχει αυτόματα το τελευταίο public release του `chkontog2026/spotdl-android`. Επιλέγει το ARM64 APK όταν είναι συμβατό και διαφορετικά χρησιμοποιεί το Universal APK. Πριν παραδώσει μια ενημέρωση στον Android installer επαληθεύει:

- το SHA-256 που επιστρέφει το GitHub Releases API, όταν είναι διαθέσιμο,
- το Android package name,
- ότι το version code είναι νεότερο,
- ότι η ψηφιακή υπογραφή είναι ίδια με την εγκατεστημένη εφαρμογή.

Το Android απαιτεί τελική επιβεβαίωση του χρήστη για την εγκατάσταση.

## Build

```powershell
.\build_android.bat
```

Δημιουργούνται:

- `android-releases/SpotDL_Android_ARM64_v1.1.5.apk`
- `android-releases/SpotDL_Android_Universal_v1.1.5.apk`

Για να μπορούν να εγκατασταθούν οι επόμενες εκδόσεις ως ενημερώσεις, όλα τα APK πρέπει να υπογράφονται με το ίδιο ιδιωτικό signing key.

Χρησιμοποίησε την εφαρμογή μόνο για περιεχόμενο που σου ανήκει ή έχεις άδεια να κατεβάσεις.
