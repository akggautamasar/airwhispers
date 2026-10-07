import { buildGoogleAssertion } from "./crypto.js";
import type { Config } from "./config.js";
import type { Device } from "./types.js";

interface ServiceAccount {
  client_email: string;
  private_key: string;
  project_id?: string;
  token_uri?: string;
}

/**
 * Firebase Cloud Messaging (HTTP v1) sender.
 *
 * Implemented directly on fetch + node:crypto (RS256 assertion → OAuth token) so
 * there is no Google SDK dependency. Data-only messages are used so the Android
 * client decides how to present — and whether to *speak* — a message.
 *
 * Disabled unless GOOGLE_SERVICE_ACCOUNT_JSON is configured; the app works without
 * it (the realtime socket covers the armed case).
 */
export class FcmPusher {
  private accessToken: { value: string; expiresAt: number } | null = null;

  constructor(
    private readonly config: Config,
    private readonly log: (level: "info" | "warn", message: string, meta?: Record<string, unknown>) => void,
  ) {}

  get enabled(): boolean {
    return Boolean(this.config.fcmServiceAccountJson);
  }

  private serviceAccount(): ServiceAccount | null {
    if (!this.config.fcmServiceAccountJson) return null;
    try {
      return JSON.parse(this.config.fcmServiceAccountJson) as ServiceAccount;
    } catch (error) {
      this.log("warn", "fcm.invalid_service_account", { error: String(error) });
      return null;
    }
  }

  private async bearerToken(): Promise<string | null> {
    if (this.accessToken && this.accessToken.expiresAt > Date.now() + 60_000) {
      return this.accessToken.value;
    }
    const account = this.serviceAccount();
    if (!account) return null;
    const assertion = buildGoogleAssertion(account, "https://www.googleapis.com/auth/firebase.messaging");
    const response = await fetch(account.token_uri ?? "https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    });
    if (!response.ok) {
      this.log("warn", "fcm.token_failed", { status: response.status });
      return null;
    }
    const payload = (await response.json()) as { access_token: string; expires_in: number };
    this.accessToken = { value: payload.access_token, expiresAt: Date.now() + payload.expires_in * 1000 };
    return this.accessToken.value;
  }

  /** Sends a data-only message to each device; returns the delivery count. */
  async sendData(devices: Device[], data: Record<string, string>, highPriority = true): Promise<number> {
    if (!this.enabled || devices.length === 0) return 0;
    const account = this.serviceAccount();
    const projectId = this.config.fcmProjectId ?? account?.project_id;
    if (!projectId) {
      this.log("warn", "fcm.missing_project_id");
      return 0;
    }
    const token = await this.bearerToken();
    if (!token) return 0;

    let delivered = 0;
    await Promise.all(
      devices.map(async (device) => {
        if (!device.pushToken) return;
        try {
          const response = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
            method: "POST",
            headers: {
              Authorization: `Bearer ${token}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              message: {
                token: device.pushToken,
                data,
                android: { priority: highPriority ? "high" : "normal" },
              },
            }),
          });
          if (response.ok) {
            delivered++;
          } else {
            const body = await response.text();
            this.log("warn", "fcm.send_failed", { status: response.status, body: body.slice(0, 200) });
          }
        } catch (error) {
          this.log("warn", "fcm.transport_error", { error: String(error) });
        }
      }),
    );
    return delivered;
  }
}
