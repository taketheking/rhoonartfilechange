const { useState } = React;

function ExcelConverter() {
  const [data, setData] = useState([]);
  const [headers, setHeaders] = useState([]);
  const [loading, setLoading] = useState(false);

  const onDrop = async (acceptedFiles) => {
    const file = acceptedFiles[0];
    if (file) {
      setLoading(true);
      const formData = new FormData();
      formData.append("file", file);

      try {
        const response = await fetch("/api/excel/upload", {
          method: "POST",
          body: formData,
        });
        const result = await response.json();
        setHeaders(result.headers);
        setData(result.data);
      } catch (error) {
        console.error("Error uploading file:", error);
        alert("파일 업로드 중 오류가 발생했습니다.");
      } finally {
        setLoading(false);
      }
    }
  };

  const handleConvertToCsv = async () => {
    try {
      const response = await fetch("/api/excel/convert", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ headers, data }),
      });

      if (!response.ok) throw new Error("변환 중 오류가 발생했습니다.");

      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "converted.csv";
      a.click();
      window.URL.revokeObjectURL(url);
    } catch (error) {
      console.error("Error converting to CSV:", error);
      alert("CSV 변환 중 오류가 발생했습니다.");
    }
  };

  return (
    <div>
      <div
        className="upload-area"
        onClick={() => {
          const input = document.createElement("input");
          input.type = "file";
          input.accept = ".xlsx,.xls";
          input.onchange = (e) => onDrop(e.target.files);
          input.click();
        }}
      >
        <p>Excel 파일을 클릭하여 선택하세요</p>
      </div>

      {loading && <p>로딩 중...</p>}

      {data.length > 0 && (
        <>
          <div className="preview-container">
            <table>
              <thead>
                <tr>
                  {headers.map((header, index) => (
                    <th key={index}>{header}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.map((row, rowIndex) => (
                  <tr key={rowIndex}>
                    {row.map((cell, cellIndex) => (
                      <td key={cellIndex}>{cell}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <button onClick={handleConvertToCsv}>CSV로 변환</button>
        </>
      )}
    </div>
  );
}

function Dashboard() {
  const [activeTab, setActiveTab] = useState("csv");

  const renderContent = () => {
    switch (activeTab) {
      case "csv":
        return <ExcelConverter />;
      case "exclude":
        return <div>정산 제외 기능 준비 중...</div>;
      case "youtube":
        return <div>유튜브 파일 분할 기능 준비 중...</div>;
      default:
        return null;
    }
  };

  return (
    <div className="dashboard-container">
      <div className="sidebar">
        <h2>PLPL Settlement</h2>
        <div
          className={`nav-item ${activeTab === "csv" ? "active" : ""}`}
          onClick={() => setActiveTab("csv")}
        >
          CSV 변환
        </div>
        <div
          className={`nav-item ${activeTab === "exclude" ? "active" : ""}`}
          onClick={() => setActiveTab("exclude")}
        >
          정산 제외
        </div>
        <div
          className={`nav-item ${activeTab === "youtube" ? "active" : ""}`}
          onClick={() => setActiveTab("youtube")}
        >
          유튜브 파일 분할
        </div>
      </div>
      <div className="main-content">{renderContent()}</div>
    </div>
  );
}

ReactDOM.render(
  <React.StrictMode>
    <Dashboard />
  </React.StrictMode>,
  document.getElementById("root")
);
