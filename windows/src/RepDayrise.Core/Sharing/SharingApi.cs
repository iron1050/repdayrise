using System.Net;
using System.Net.Http.Headers;
using System.Text;

namespace RepDayrise.Core.Sharing;

/// <summary>The server answered, but not with success. <see cref="Exception.Message"/> is safe to show.</summary>
public sealed class ApiException(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}

/// <summary>Thin client for the Dayrise sharing backend (see /backend). Every call names its server, since lists can live on different ones.</summary>
internal sealed class SharingApi(HttpClient? http = null)
{
    private readonly HttpClient _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(15) };

    private sealed record Reply(int Status, string Body);

    private async Task<Reply> Call(string baseUrl, HttpMethod method, string path, string? token = null, string? body = null, string? etag = null)
    {
        using var request = new HttpRequestMessage(method, baseUrl + path);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (token != null) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (etag != null) request.Headers.TryAddWithoutValidation("If-None-Match", etag);
        if (body != null) request.Content = new StringContent(body, Encoding.UTF8, "application/json");

        using var response = await _http.SendAsync(request).ConfigureAwait(false);
        var status = (int)response.StatusCode;
        var text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
        if (response.IsSuccessStatusCode || response.StatusCode == HttpStatusCode.NotModified) return new Reply(status, text);

        string? message = null;
        try { message = SharingJson.Decode<ErrorBody>(text).Message; } catch { /* not JSON */ }
        throw new ApiException(status, string.IsNullOrWhiteSpace(message) ? $"The server said no ({status})." : message);
    }

    public async Task<CreateShareResponse> CreateShare(string baseUrl, string name) =>
        SharingJson.Decode<CreateShareResponse>((await Call(baseUrl, HttpMethod.Post, "/v1/shares", body: SharingJson.Encode(new NameBody(name)))).Body);

    public Task Publish(string baseUrl, string shareId, string token, string name, Snapshot snapshot) =>
        Call(baseUrl, HttpMethod.Put, $"/v1/shares/{shareId}", token, SharingJson.Encode(new PublishBody(name, snapshot)));

    public async Task<StatusResponse> Status(string baseUrl, string shareId, string token) =>
        SharingJson.Decode<StatusResponse>((await Call(baseUrl, HttpMethod.Get, $"/v1/shares/{shareId}", token)).Body);

    public async Task<string> RotateCode(string baseUrl, string shareId, string token) =>
        SharingJson.Decode<CodeResponse>((await Call(baseUrl, HttpMethod.Post, $"/v1/shares/{shareId}/rotate-code", token)).Body).Code;

    public Task RemovePartner(string baseUrl, string shareId, string token, string partnerId) =>
        Call(baseUrl, HttpMethod.Delete, $"/v1/shares/{shareId}/subscribers/{partnerId}", token);

    public Task DeleteShare(string baseUrl, string shareId, string token) =>
        Call(baseUrl, HttpMethod.Delete, $"/v1/shares/{shareId}", token);

    public async Task<JoinResponse> Join(string baseUrl, string code, string name) =>
        SharingJson.Decode<JoinResponse>((await Call(baseUrl, HttpMethod.Post, "/v1/join", body: SharingJson.Encode(new JoinBody(code, name)))).Body);

    /// <summary>Returns null when the list hasn't changed since <paramref name="version"/>.</summary>
    public async Task<FeedResponse?> Feed(string baseUrl, string shareId, string token, int version)
    {
        var reply = await Call(baseUrl, HttpMethod.Get, "/v1/feed", token, etag: $"\"{shareId}-{version}\"");
        return reply.Status == 304 ? null : SharingJson.Decode<FeedResponse>(reply.Body);
    }

    public Task Unsubscribe(string baseUrl, string token) => Call(baseUrl, HttpMethod.Delete, "/v1/feed", token);
}
