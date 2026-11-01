package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration
import com.pawbridge.backend.boot.configuration.AdminPostStatisticsConfiguration
import com.pawbridge.backend.boot.configuration.AdminUserDirectoryConfiguration
import com.pawbridge.backend.boot.configuration.AdminUserUpdateConfiguration
import com.pawbridge.backend.boot.configuration.AnimalApmsCollectionConfiguration
import com.pawbridge.backend.boot.configuration.AnimalChatbotConfiguration
import com.pawbridge.backend.boot.configuration.AnimalCommandConfiguration
import com.pawbridge.backend.boot.configuration.AnimalDiscoveryConfiguration
import com.pawbridge.backend.boot.configuration.AnimalGalleryFeedConfiguration
import com.pawbridge.backend.boot.configuration.AnimalImageConfiguration
import com.pawbridge.backend.boot.configuration.AnimalLostSearchConfiguration
import com.pawbridge.backend.boot.configuration.AnimalPhotoArchiveConfiguration
import com.pawbridge.backend.boot.configuration.AnimalRecommendationsConfiguration
import com.pawbridge.backend.boot.configuration.AnimalReportConfiguration
import com.pawbridge.backend.boot.configuration.AnimalSearchProjectionConfiguration
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration
import com.pawbridge.backend.boot.configuration.AnimalTravelCollectionConfiguration
import com.pawbridge.backend.boot.configuration.AnimalTravelConfiguration
import com.pawbridge.backend.boot.configuration.AuthSessionConfiguration
import com.pawbridge.backend.boot.configuration.CommentWriteConfiguration
import com.pawbridge.backend.boot.configuration.CommunityMaintenanceConfiguration
import com.pawbridge.backend.boot.configuration.FavoriteStatusConfiguration
import com.pawbridge.backend.boot.configuration.GatewayFeaturesConfiguration
import com.pawbridge.backend.boot.configuration.HomeVideoManagementConfiguration
import com.pawbridge.backend.boot.configuration.HomeVideoReadConfiguration
import com.pawbridge.backend.boot.configuration.ManagementSecurityConfiguration
import com.pawbridge.backend.boot.configuration.MemberChatConfiguration
import com.pawbridge.backend.boot.configuration.MemberCleanupConfiguration
import com.pawbridge.backend.boot.configuration.NoteBlockCreateConfiguration
import com.pawbridge.backend.boot.configuration.NoteBlockListReadConfiguration
import com.pawbridge.backend.boot.configuration.NoteBlockRemoveConfiguration
import com.pawbridge.backend.boot.configuration.NoteBlockStatusReadConfiguration
import com.pawbridge.backend.boot.configuration.NoteDeletionConfiguration
import com.pawbridge.backend.boot.configuration.NoteExpiryConfiguration
import com.pawbridge.backend.boot.configuration.NoteFavoriteConfiguration
import com.pawbridge.backend.boot.configuration.NoteNotificationsReadConfiguration
import com.pawbridge.backend.boot.configuration.NoteReadStreamConfiguration
import com.pawbridge.backend.boot.configuration.NoteRecipientReadConfiguration
import com.pawbridge.backend.boot.configuration.NoteSendConfiguration
import com.pawbridge.backend.boot.configuration.PaymentCompensationMetricsConfiguration
import com.pawbridge.backend.boot.configuration.PaymentConfirmationConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteConfiguration
import com.pawbridge.backend.boot.configuration.PrivateNoteListConfiguration
import com.pawbridge.backend.boot.configuration.PrivateNoteReadConfiguration
import com.pawbridge.backend.boot.configuration.ReadConfiguration
import com.pawbridge.backend.boot.configuration.ShelterCommandConfiguration
import com.pawbridge.backend.boot.configuration.ShelterDirectoryConfiguration
import com.pawbridge.backend.boot.configuration.ShelterMembershipConfiguration
import com.pawbridge.backend.boot.configuration.ShelterObservationConfiguration
import com.pawbridge.backend.boot.configuration.ShutdownDiagnosticsConfiguration
import com.pawbridge.backend.boot.configuration.SignupStatisticsReadConfiguration
import com.pawbridge.backend.boot.configuration.SocialOAuthConfiguration
import com.pawbridge.backend.boot.configuration.StoreCartConfiguration
import com.pawbridge.backend.boot.configuration.StoreCatalogConfiguration
import com.pawbridge.backend.boot.configuration.StoreCatalogManagementConfiguration
import com.pawbridge.backend.boot.configuration.StoreDirectOrderConfiguration
import com.pawbridge.backend.boot.configuration.StoreMaintenanceConfiguration
import com.pawbridge.backend.boot.configuration.StoreMockPaymentConfiguration
import com.pawbridge.backend.boot.configuration.StoreOrderAdministrationConfiguration
import com.pawbridge.backend.boot.configuration.StorePaymentEventsConfiguration
import com.pawbridge.backend.boot.configuration.StoreWishlistConfiguration
import com.pawbridge.backend.boot.configuration.TotalUserCountReadConfiguration
import com.pawbridge.backend.boot.configuration.UserAnimalActivitiesConfiguration
import com.pawbridge.backend.boot.configuration.UserNicknameConfiguration
import com.pawbridge.backend.boot.configuration.UserProfileConfiguration
import com.pawbridge.backend.boot.configuration.UserStoreMyPageConfiguration
import com.pawbridge.backend.boot.configuration.VisitCollectionConfiguration
import com.pawbridge.backend.boot.configuration.VisitReadConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import java.util.function.Function
import org.springframework.boot.SpringApplication
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import

@SpringBootConfiguration
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration",
    ],
    exclude = [
        DataSourceAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
        JdbcTemplateAutoConfiguration::class,
        UserDetailsServiceAutoConfiguration::class,
        RedisAutoConfiguration::class,
        RedisRepositoriesAutoConfiguration::class,
        RedisReactiveAutoConfiguration::class,
        OAuth2ClientAutoConfiguration::class,
    ],
)
@Import(
    ManagementSecurityConfiguration::class,
    PaymentCompensationMetricsConfiguration::class,
    GatewayFeaturesConfiguration::class,
    ShutdownDiagnosticsConfiguration::class,
    StoreMockPaymentConfiguration::class,
    ShelterDirectoryConfiguration::class,
    AnimalImageConfiguration::class,
    PaymentConfirmationConfiguration::class,
    StorePaymentEventsConfiguration::class,
    StoreCatalogManagementConfiguration::class,
    ShelterObservationConfiguration::class,
    AnimalChatbotConfiguration::class,
    AnimalSearchProjectionConfiguration::class,
    AnimalPhotoArchiveConfiguration::class,
    AnimalApmsCollectionConfiguration::class,
    AnimalTravelCollectionConfiguration::class,
    AnimalGalleryFeedConfiguration::class,
    ShelterCommandConfiguration::class,
    StoreOrderAdministrationConfiguration::class,
    AnimalTravelConfiguration::class,
    StoreWishlistConfiguration::class,
    AnimalStatisticsConfiguration::class,
    AnimalShelterConfiguration::class,
    AnimalDiscoveryConfiguration::class,
    AnimalCommandConfiguration::class,
    AnimalLostSearchConfiguration::class,
    StoreDirectOrderConfiguration::class,
    StoreCartConfiguration::class,
    AnimalRecommendationsConfiguration::class,
    StoreCatalogConfiguration::class,
    CommunityMaintenanceConfiguration::class,
    StoreMaintenanceConfiguration::class,
    UserStoreMyPageConfiguration::class,
    HomeVideoManagementConfiguration::class,
    AnimalReportConfiguration::class,
    UserAnimalActivitiesConfiguration::class,
    ShelterMembershipConfiguration::class,
    AdminUserDirectoryConfiguration::class,
    SocialOAuthConfiguration::class,
    MemberChatConfiguration::class,
    ReadConfiguration::class,
    AdminPostReadConfiguration::class,
    AdminPostStatisticsConfiguration::class,
    PostWriteConfiguration::class,
    CommentWriteConfiguration::class,
    UserProfileConfiguration::class,
    UserNicknameConfiguration::class,
    FavoriteStatusConfiguration::class,
    HomeVideoReadConfiguration::class,
    VisitReadConfiguration::class,
    VisitCollectionConfiguration::class,
    NoteRecipientReadConfiguration::class,
    NoteBlockStatusReadConfiguration::class,
    TotalUserCountReadConfiguration::class,
    SignupStatisticsReadConfiguration::class,
    AdminUserUpdateConfiguration::class,
    AuthSessionConfiguration::class,
    NoteBlockListReadConfiguration::class,
    NoteNotificationsReadConfiguration::class,
    PrivateNoteReadConfiguration::class,
    PrivateNoteListConfiguration::class,
    NoteBlockRemoveConfiguration::class,
    NoteBlockCreateConfiguration::class,
    NoteReadStreamConfiguration::class,
    NoteFavoriteConfiguration::class,
    NoteDeletionConfiguration::class,
    NoteSendConfiguration::class,
    NoteExpiryConfiguration::class,
    MemberCleanupConfiguration::class,
)
class PawBridgeApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // Validate explicit inputs before Spring can open a listener or a database connection.
            val environment = Function<String, String?> { name -> System.getenv(name) }
            val settings = ReadDatabaseSettings.fromEnvironment(environment)
            val writeSettings = PostWriteSettings.fromEnvironment(environment)
            val application = SpringApplication(PawBridgeApplication::class.java)
            // The startup log and logger name use the class that declares main; with the Kotlin companion
            // object the stack-trace lookup would find PawBridgeApplication.Companion instead.
            application.setMainApplicationClass(PawBridgeApplication::class.java)
            application.addInitializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                    context.beanFactory.registerSingleton("readDatabaseSettings", settings)
                    context.beanFactory.registerSingleton("postWriteSettings", writeSettings)
                },
            )
            application.run(*args)
        }
    }
}
