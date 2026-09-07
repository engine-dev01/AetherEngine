// i18n/strings.dart — Aether Engine i18n constants (UI labels)
// Supports 5 langs: EN, FIL (Filipino), MAL, ID (Indonesian), ES
// Subscription/device management strings EXCLUDED (API-dependent — out of scope)
class S {
  // App name
  static const appName = 'Aether Engine';
  static const appNameTitle = '8 Ball Pool';

  // ─── Home Screen labels ───
  // EN
  static const enHome = 'Home';
  static const enSupportedGames = 'Supported Games';
  static const enGetSubscription = 'Get Subscription';
  static const enBestChoice = 'Best Choice';
  static const enBestChoiceSubtitle = 'Aether Engine 8 Ball Pool';
  static const enReadMore = 'Read More';
  static const enOffline = 'You are not connected to the internet, Aether Engine needs an active internet connection';

  // FIL (Filipino)
  static const filHome = 'Home';
  static const filSupportedGames = 'Permainan Yang Disokong';
  static const filGetSubscription = 'Kunin ang Subscription';
  static const filBestChoice = 'Pinakamahusay na Pagpipilian';
  static const filOffline = 'Hindi ka konektado sa internet, kailangan ng Aether Engine ng aktibong koneksyon sa internet';

  // MAL (Malay)
  static const malSupportedGames = 'Permainan Yang Disokong';
  static const malGetSubscription = 'Dapatkan Langganan';
  static const malOffline = 'Anda tidak disambungkan ke internet, Aether Engine memerlukan sambungan internet yang aktif';

  // ID (Indonesian)
  static const idSupportedGames = 'Permainan yang Didukung';
  static const idGetSubscription = 'Dapatkan Subscription';
  static const idOffline = 'Anda tidak terhubung ke internet, Aether Engine membutuhkan koneksi internet yang aktif';

  // ES (Spanish)
  static const esSupportedGames = 'Juegos Compatibles';
  static const esGetSubscription = 'Obtener Suscripción';
  static const esOffline = 'No estás conectado a internet, Aether Engine necesita una conexión a internet activa';

  // ─── Game metadata moved to data/games.dart (Phase 10) ───
  // Use Games.defaultGame.name / .packageName / .version instead
  // (extensible — add new game by editing data/games.dart only)

  // ─── User (top bar) — hardcoded placeholder ───
  static const defaultUserId = '366410';
  static const defaultVipBadge = 'VIP';

  // ─── Timer placeholders ───
  static const defaultTimer = '00:00:00:00';

  // ─── Bottom nav labels ───
  static const navAdd = 'Add';
  static const navCart = 'Cart';
  static const navChat = 'Chat';
  static const navSettings = 'Settings';

  // ─── i18n picker (system locale → fallback English) ───
  static String homeLabel(String locale) {
    switch (locale) {
      case 'fil': return filHome;
      case 'ms':  return filHome;  // Malay uses Filipino strings
      case 'id':  return filHome;  // Indonesian similar
      default:    return enHome;
    }
  }

  static String supportedGamesLabel(String locale) {
    switch (locale) {
      case 'fil':
      case 'ms':  return filSupportedGames;
      case 'id':  return idSupportedGames;
      case 'es':  return esSupportedGames;
      default:    return enSupportedGames;
    }
  }

  static String getSubscriptionLabel(String locale) {
    switch (locale) {
      case 'fil':
      case 'ms':  return filGetSubscription;
      case 'id':  return idGetSubscription;
      case 'es':  return esGetSubscription;
      default:    return enGetSubscription;
    }
  }

  static String bestChoiceLabel(String locale) {
    switch (locale) {
      case 'fil': return filBestChoice;
      default:    return enBestChoice;
    }
  }

  static String offlineLabel(String locale) {
    switch (locale) {
      case 'fil':
      case 'ms':  return filOffline;
      case 'id':  return idOffline;
      case 'es':  return esOffline;
      default:    return enOffline;
    }
  }
}
