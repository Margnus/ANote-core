import Darwin
import UIKit

/// Disposable UIKit host for the same isolated DEV-041 transaction probe.
@main
final class SqliteJointCheckpointApp: UIResponder, UIApplicationDelegate {}

@objc(Dev041JointSceneDelegate)
final class SqliteJointCheckpointScene: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIViewController()
        self.window = window
        window.makeKeyAndVisible()

        DispatchQueue.main.async {
            do {
                try SqliteJointCheckpoint.runApp()
            } catch {
                NSLog("DEV-041 joint checkpoint failed: \(error)")
                exit(2)
            }
        }
    }
}
