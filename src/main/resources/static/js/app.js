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

  const handleCellChange = (rowIndex, columnIndex, value) => {
    const newData = [...data];
    newData[rowIndex][columnIndex] = value;
    setData(newData);
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
          <div className="table-container">
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
                      <td key={cellIndex}>
                        <input
                          type="text"
                          value={cell}
                          onChange={(e) =>
                            handleCellChange(
                              rowIndex,
                              cellIndex,
                              e.target.value
                            )
                          }
                        />
                      </td>
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

function LoginForm() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");

    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ username, password }),
      });

      if (response.ok) {
        window.location.href = "/dashboard";
      } else {
        setError("아이디 또는 비밀번호가 올바르지 않습니다.");
      }
    } catch (error) {
      setError("로그인 중 오류가 발생했습니다.");
    }
  };

  return (
    <div className="login-container">
      <div className="login-box">
        <h1 className="login-title">PLPL Settlement</h1>
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label htmlFor="username">아이디</label>
            <input
              type="text"
              id="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
          </div>
          <div className="form-group">
            <label htmlFor="password">비밀번호</label>
            <input
              type="password"
              id="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </div>
          {error && (
            <p style={{ color: "red", marginBottom: "1rem" }}>{error}</p>
          )}
          <button type="submit" className="login-button">
            로그인
          </button>
        </form>
      </div>
    </div>
  );
}

function App() {
  return (
    <div className="container">
      <h1 className="title">Excel to CSV Converter</h1>
      <ExcelConverter />
    </div>
  );
}

ReactDOM.render(
  <React.StrictMode>
    <LoginForm />
  </React.StrictMode>,
  document.getElementById("root")
);
