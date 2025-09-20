package org.ngengine.platform.transport;

public class RTCTransportIceCandidate {
    private final String candidate;
    private final String sdpMid;

    public RTCTransportIceCandidate(String candidate, String sdpMid) {
        this.candidate = candidate;
        this.sdpMid = sdpMid;
    }

    public String getCandidate() {
        return candidate;
    }

    public String getSdpMid() {
        return sdpMid;
    }

    @Override
    public String toString() {
        return "RTCTransportIceCandidate{" +
                "candidate='" + candidate + '\'' +
                ", sdpMid='" + sdpMid + '\'' +
                '}';
    }

    @Override
    public int hashCode() {
        return candidate.hashCode() + sdpMid.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        RTCTransportIceCandidate other = (RTCTransportIceCandidate) obj;
        return candidate.equals(other.candidate) && sdpMid.equals(other.sdpMid);
    }
}
