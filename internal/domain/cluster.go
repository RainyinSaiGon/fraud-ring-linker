package domain

// Cluster is one deterministic batch output component.
type Cluster struct {
	ClusterID  string   `json:"cluster_id"`
	AccountIDs []string `json:"account_ids"`
	Confidence float64  `json:"confidence"`
}

// ClustersOutput is the required batch output envelope.
type ClustersOutput struct {
	Clusters []Cluster `json:"clusters"`
}

// Assignment is the required one-line streaming response.
type Assignment struct {
	AccountID  string  `json:"account_id"`
	ClusterID  string  `json:"cluster_id"`
	Confidence float64 `json:"confidence"`
}
